package eu.kanade.tachiyomi.extension.es.leercapituloweb

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapituloWeb : HttpSource() {
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href*='/manga/']").mapNotNull { element ->
            val url = element.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = element.attr("title").ifBlank { element.text() }.trim()
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
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search-autocomplete")
            .addQueryParameter("term", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val direct = parseSearchResponse(response)
        if (direct.isNotEmpty()) return MangasPage(direct, false)

        val request = response.request
        val call = network.client.newCall(request)

        val html = runCatching {
            runWebViewBlocking<String>(call, timeout = 30.seconds) {
                javaScriptEnabled = true
                domStorageEnabled = true
                onPageFinished {
                    evaluateJs("document.documentElement.outerHTML") { pageHtml ->
                        resolve(pageHtml)
                    }
                }
                loadUrl(request.url.toString())
            }
        }.getOrNull()

        if (!html.isNullOrBlank()) {
            val document = Jsoup.parse(html, request.url.toString())
            val fromHtml = parseSearchDocument(document)
            if (fromHtml.isNotEmpty()) return MangasPage(fromHtml, false)

            val bodyText = document.body()?.text().orEmpty()
            val fromJson = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<Dto>>(bodyText)
            }.getOrDefault(emptyList()).map { dto ->
                SManga.create().apply {
                    setUrlWithoutDomain(dto.link)
                    title = dto.label
                    thumbnail_url = dto.thumbnail.takeIf { it.isNotBlank() }?.let {
                        if (it.startsWith("http")) it else baseUrl + it
                    }
                }
            }
            if (fromJson.isNotEmpty()) return MangasPage(fromJson, false)
        }

        return MangasPage(emptyList(), false)
    }

    private fun parseSearchResponse(response: Response): List<SManga> {
        val document = response.asJsoup()
        val fromHtml = parseSearchDocument(document)
        if (fromHtml.isNotEmpty()) return fromHtml

        return runCatching {
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
    }

    private fun parseSearchDocument(document: org.jsoup.nodes.Document): List<SManga> {
        return document.select("a[href*='/manga/']").mapNotNull { element ->
            val url = element.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = element.attr("title").ifBlank { element.text() }.trim()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }.distinctBy { it.url }
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
            description = document.selectFirst("#example2")?.text()
            genre = document.select("a[href*='/genre/']").joinToString { it.text() }
            thumbnail_url = document.selectFirst("img[alt*='Portada'], .cover-detail img, img")?.imgAttr()
            status = SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[href*='/leer/']").mapNotNull { link ->
            val href = link.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
            }
        }.distinctBy { it.url }
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup()
        .selectFirst(".comic_wraCon img, img")
        ?.imgAttr()
        .orEmpty()

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val call = network.client.newCall(response.request)

        val html = runWebViewBlocking<String>(call, timeout = 60.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            var reloaded = false

            onPageFinished {
                if (!reloaded) {
                    reloaded = true
                    evaluateJs("localStorage.setItem('display_mode','1'); location.reload();")
                } else {
                    poll(500.milliseconds) {
                        evaluateJs("document.querySelectorAll('.comic_wraCon img').length.toString()") { count ->
                            if ((count.toIntOrNull() ?: 0) > 0) {
                                evaluateJs("document.documentElement.outerHTML") { pageHtml ->
                                    resolve(pageHtml)
                                }
                            }
                        }
                    }
                }
            }
            loadUrl(chapterUrl)
        }

        val document = Jsoup.parse(html, chapterUrl)
        val images = document.select(".comic_wraCon img, img").mapNotNull { image ->
            when {
                image.hasAttr("data-original") -> image.attr("abs:data-original")
                image.hasAttr("data-src") -> image.attr("abs:data-src")
                else -> image.attr("abs:src")
            }.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }.distinct()

        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-original") -> attr("abs:data-original")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
