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
import keiyoushi.utils.runWebViewBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapituloWeb : HttpSource() {
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "az")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()

        // Nuevo diseño de LeerCapitulo: cada resultado está dentro de article.lc-card.
        val mangas = document.select("article.lc-card").mapNotNull { card ->
            val nameLink = card.selectFirst("a.lc-card-name")
            val coverLink = card.selectFirst("a.lc-card-cover")
            val link = nameLink ?: coverLink ?: return@mapNotNull null

            val url = link.attr("abs:href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = nameLink?.text()?.trim()
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = card.selectFirst("a.lc-card-cover img, img")?.imgAttr()
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(
            "ul.pagination li.active + li:not(.disabled) a",
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda usa el buscador de LeerCapitulo."),
    )

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "za")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("article h1, h1")?.text()?.trim().orEmpty()
            description = document.selectFirst("#sinopsis p, #sinopsis")?.text()?.trim()
            genre = document.select("article .badge").joinToString { it.text().trim() }
            thumbnail_url = document.selectFirst(".lc-cover-lg img, article img")?.imgAttr()
            status = SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapterList a.lc-chapter-row").mapNotNull { link ->
            val href = link.attr("abs:href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = link.selectFirst("span.n")?.text()?.trim()
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
            }
        }.distinctBy { it.url }
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup()
        .selectFirst("#lcPages img, .lc-pages img, .comic_wraCon img, img")
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
                        evaluateJs(
                            "document.querySelectorAll('#lcPages img, .comic_wraCon img').length.toString()",
                        ) { count ->
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

        val document = org.jsoup.Jsoup.parse(html, chapterUrl)
        val images = document.select(
            "#lcPages img, .lc-pages img, .comic_wraCon img, .reading-content img, img",
        ).mapNotNull { image ->
            image.imgAttr().takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }.distinct()

        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-original") -> attr("abs:data-original")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        else -> attr("abs:src")
    }
}
