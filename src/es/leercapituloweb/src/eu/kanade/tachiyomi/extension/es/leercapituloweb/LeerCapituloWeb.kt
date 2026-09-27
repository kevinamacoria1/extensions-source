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
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapituloWeb : HttpSource() {
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".hot-manga > .thumbnails > a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }
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
        val document = response.asJsoup()

        // The current site can return the manga page directly when the API is unavailable.
        if (response.request.url.pathSegments.contains("manga")) {
            val manga = mangaDetailsParse(response)
            return MangasPage(listOf(manga), false)
        }

        val mangas = runCatching {
            response.parseAs<List<Dto>>().map { dto ->
                SManga.create().apply {
                    setUrlWithoutDomain(dto.link)
                    title = dto.label
                    thumbnail_url = baseUrl + dto.thumbnail
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
            description = document.selectFirst("#example2")?.text()
            genre = document.select(".description-update a[href^='/genre/']").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".cover-detail > img")?.imgAttr()
            status = SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".chapter-list > ul > li").mapNotNull { element ->
            element.selectFirst("a.xanh")?.let { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.attr("abs:href"))
                    name = link.text()
                }
            }
        }
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
                    evaluateJs(
                        "localStorage.setItem('display_mode','1'); location.reload();",
                    )
                } else {
                    poll(500.milliseconds) {
                        evaluateJs(
                            "document.querySelectorAll('.comic_wraCon img').length.toString()",
                        ) { count ->
                            val n = count.toIntOrNull() ?: 0
                            if (n > 0) {
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
        val images = document.select(".comic_wraCon img").mapNotNull { image ->
            when {
                image.hasAttr("data-original") -> image.attr("abs:data-original")
                image.hasAttr("data-src") -> image.attr("abs:data-src")
                else -> image.attr("abs:src")
            }.takeIf { it.isNotBlank() }
        }.distinct()

        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-original") -> attr("abs:data-original")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
