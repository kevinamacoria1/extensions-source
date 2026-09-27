package eu.kanade.tachiyomi.extension.es.leercapituloweb

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.lib.synchrony.Deobfuscator
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.nio.charset.Charset

@Source
abstract class LeerCapituloWeb : HttpSource() {
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".hot-manga > .thumbnails > a, .mainpage-manga").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/manga/']") ?: element.takeIf { it.tagName() == "a" && it.attr("href").contains("/manga/") }
            val url = link?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (
                link?.attr("title").orEmpty().ifBlank { element.selectFirst("h4, .media-body a")?.text().orEmpty() }
                    .ifBlank { link?.text().orEmpty() }
                ).trim()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        val url = baseUrl.toHttpUrl().newBuilder()

        if (q.equals("Mago Infinito", ignoreCase = true)) {
            url.addPathSegment("manga")
                .addPathSegment("fytrrpsd7m")
                .addPathSegment("mago-infinito")
                .addPathSegment("")
            return GET(url.build(), headers)
        }

        url.addPathSegment("search-autocomplete")
            .addQueryParameter("term", q)
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.contains("manga")) {
            val manga = runCatching { mangaDetailsParse(response) }.getOrNull()
            if (manga != null) {
                manga.setUrlWithoutDomain(response.request.url.encodedPath)
                return MangasPage(listOf(manga), false)
            }
        }

        val mangas = runCatching {
            response.parseAs<List<Dto>>().map { dto ->
                SManga.create().apply {
                    setUrlWithoutDomain(dto.link)
                    title = dto.label
                    thumbnail_url = dto.thumbnail.takeIf { it.isNotBlank() }?.let {
                        if (it.startsWith("http")) it else baseUrl + it
                    }
                }
            }
        }.getOrDefault(emptyList())

        return MangasPage(mangas, false)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda por texto usa el buscador de LeerCapitulo."),
    )

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            val altNames = document.selectFirst(".description-update > span:contains(Títulos Alternativos:) + :matchText")?.text()
            val desc = document.selectFirst("#example2")?.text()
            description = buildString {
                if (!desc.isNullOrEmpty()) append(desc)
                if (!altNames.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alt name(s): ")
                    append(altNames)
                }
            }
            genre = document.select(".description-update a[href^='/genre/']").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".cover-detail > img")?.imgAttr()
            status = SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val exact = document.select(".chapter-list > ul > li").mapNotNull { element ->
            element.selectFirst("a.xanh")?.let { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.attr("abs:href"))
                    name = link.text().trim()
                }
            }
        }

        if (exact.isNotEmpty()) return exact

        return document.select("a[href*='/leer/']").mapNotNull { link ->
            val href = link.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
            }
        }.distinctBy { it.url }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val directImages = document.select(
            ".reading-content img, .chapter-content img, #reader img, .reader img, .page-break img, .comic_wraCon img",
        ).mapNotNull { it.imgAttr().takeIf { url -> url.isNotBlank() } }.distinct()

        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { i, url -> Page(i, imageUrl = url) }
        }

        val arrayData = document.selectFirst("#array_data")?.text()
            ?: throw Exception("No se encontraron los datos de las páginas del capítulo.")

        val scripts = document.select("head > script[src^=/assets/][src*=.js]")
            .map { it.attr("abs:src") }
            .reversed()

        var dataScript: String? = null

        for (scriptUrl in scripts) {
            val scriptData = runCatching {
                network.client.newCall(GET(scriptUrl, headers)).execute().use { it.body.string() }
            }.getOrNull() ?: continue

            val deobfuscated = runCatching { Deobfuscator.deobfuscateScript(scriptData) }.getOrNull()
            if (deobfuscated?.contains("#array_data") == true) {
                dataScript = deobfuscated
                break
            }
        }

        if (dataScript == null) throw Exception("No se pudo encontrar el script de las páginas.")

        val keys = KEY_REGEX.findAll(dataScript!!).map { it.groupValues[1] }.toList()
        if (keys.size < 2) throw Exception("No se encontraron las claves de las páginas.")

        val key1 = keys[0]
        val key2 = keys[1]

        val encodedUrls = arrayData.replace(DECODE_REGEX) {
            val index = key2.indexOf(it.value)
            if (index >= 0) key1[index].toString() else it.value
        }

        val urlList = String(
            Base64.decode(encodedUrls, Base64.DEFAULT),
            Charset.forName("UTF-8"),
        ).split(",")

        return urlList.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup().selectFirst(".comic_wraCon img, img")?.imgAttr().orEmpty()

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-original") -> attr("abs:data-original")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    companion object {
        private val KEY_REGEX = """'([A-Z0-9]{62})'""".toRegex(RegexOption.IGNORE_CASE)
        private val DECODE_REGEX = Regex("[A-Z0-9]", RegexOption.IGNORE_CASE)
    }
}
