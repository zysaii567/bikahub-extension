package eu.kanade.tachiyomi.extension.zh.manwa

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.aesCbc
import keiyoushi.utils.aesDecrypt
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Manwa : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder =
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")

    private val rewriteOctetStream: Interceptor = Interceptor { chain ->
        val originalResponse: Response = chain.proceed(chain.request())
        if (originalResponse.request.url.toString().contains("?v=20220724")) {
            val key = "my2ecret782ecret".toByteArray()
            originalResponse.aesDecrypt(
                cipher = aesCbc(key, key, padding = false),
                contentType = "image/webp".toMediaTypeOrNull(),
            )
        } else {
            originalResponse
        }
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        addNetworkInterceptor(rewriteOctetStream)

    // Popular: 使用 /booklist，漫画数量多且支持正常分页
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/booklist?page=$page".toHttpUrl()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseBookListPage(document)
    }

    // Latest: 使用 /rank (最新榜)
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/rank", Fetch.DOCUMENT).asJsoup()
        val elements = document.select("ul#rankList_1 li, ul#rankList_1 a[href^=/book/]")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in elements) {
            val a = if (el.tagName() == "a") el else el.selectFirst("a[href^=/book/]") ?: el.parent()
            if (a == null) continue
            val href = a.attr("href").trim()
            if (!href.startsWith("/book/") || !seen.add(href)) continue

            val titleText = a.attr("title").ifEmpty { a.text().trim() }
            val img = a.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-original").ifEmpty { it.attr("src") }
            }

            mangas.add(
                SManga.create().apply {
                    title = titleText
                    setUrlWithoutDomain(a.absUrl("href"))
                    thumbnail_url = coverUrl
                },
            )
        }
        return MangasPage(mangas, false)
    }

    // Search
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val cleanQuery = query.trim()
        val isBookList = cleanQuery.isEmpty()
        val urlBuilder = (if (isBookList) "$baseUrl/booklist" else "$baseUrl/search").toHttpUrl().newBuilder()

        if (cleanQuery.isNotEmpty()) {
            urlBuilder.addQueryParameter("keyword", cleanQuery)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is UriPartFilter -> filter.setParamPair(urlBuilder)
                    is TagCheckBoxFilterGroup -> filter.setParamPair(urlBuilder)
                    else -> {}
                }
            }
        }
        if (page > 1) {
            urlBuilder.addQueryParameter("page", page.toString())
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return if (isBookList) {
            parseBookListPage(document)
        } else {
            parseSearchPage(document)
        }
    }

    private fun parseBookListPage(document: Document): MangasPage {
        val elements = document.select("div.manga-list-2-cover a[href^=/book/]")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (a in elements) {
            val href = a.attr("href").trim()
            if (!href.startsWith("/book/") || !seen.add(href)) continue

            val titleText = a.attr("title").ifEmpty {
                a.parent()?.parent()?.selectFirst("p.manga-list-2-title")?.text()?.trim() ?: ""
            }
            val img = a.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-original").ifEmpty { it.attr("src") }
            }

            mangas.add(
                SManga.create().apply {
                    title = titleText
                    setUrlWithoutDomain(a.absUrl("href"))
                    thumbnail_url = coverUrl
                },
            )
        }
        val hasNext = document.select("ul.pagination2 li, div.pagination a").any { it.text().contains("下") }
        return MangasPage(mangas, hasNext)
    }

    private fun parseSearchPage(document: Document): MangasPage {
        val items = document.select("ul.book-list > li")
        val mangas = items.mapNotNull { li ->
            val a = li.selectFirst("a[href^=/book/]") ?: return@mapNotNull null
            val titleText = li.selectFirst("p.book-list-info-title")?.text()?.trim()
                ?: a.attr("title")
            val img = li.selectFirst("img")
            val coverUrl = img?.let {
                it.attr("data-original").ifEmpty { it.attr("src") }
            }

            SManga.create().apply {
                title = titleText
                setUrlWithoutDomain(a.absUrl("href"))
                thumbnail_url = coverUrl
            }
        }
        val hasNext = document.select("ul.pagination2 li, div.pagination a").any { it.text().contains("下") }
        return MangasPage(mangas, hasNext)
    }

    // Details & Chapters
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val details = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst(".detail-main-info-title")?.text()?.trim() ?: manga.title
                thumbnail_url = document.selectFirst("div.detail-main-cover img")?.let {
                    it.attr("data-original").ifEmpty { it.attr("src") }
                }
                author = document.select("p.detail-main-info-author span.detail-main-info-value a").text().trim().ifEmpty { null }
                artist = author
                status = when {
                    document.text().contains("连载中") -> SManga.ONGOING
                    document.text().contains("已完结") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                genre = document.select("div.detail-main-info-class a.info-tag").eachText().joinToString(", ")
                description = document.selectFirst("#detail p.detail-desc")?.text()?.trim()
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            document.select("ul#detail-list-select li a, div.chapteritem a").map { element ->
                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = element.text().trim().ifEmpty { "第1话" }
                }
            }.reversed().ifEmpty { chapters }
        } else {
            chapters
        }

        chapterList.firstOrNull()?.date_upload = DATE_FORMAT.tryParseDate(
            document.selectFirst(".detail-list-title-3")?.text()?.substringBefore("更新"),
        )
        return SMangaUpdate(details, chapterList)
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return document.select("#cp_img div.img-content img[data-r-src]").mapIndexed { index, it ->
            Page(index, imageUrl = it.attr("abs:data-r-src"))
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/book/")) return null
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    // Filters
    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/booklist", Fetch.DOCUMENT).asJsoup()
        val tags = LinkedHashMap<String, String>()
        document.select("div.manga-filter-row.tags a").forEach { a ->
            tags[a.text().trim()] = a.attr("data-val")
        }
        if (tags.isEmpty()) {
            tags["全部"] = ""
        }
        return tags.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            EndFilter(),
            CGenderFilter(),
            AreaFilter(),
            SortFilter(),
        )
        data?.parseAs<LinkedHashMap<String, String>>()?.let {
            filters.add(TagCheckBoxFilterGroup("标签", it))
        }
        return FilterList(filters)
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ROOT)
    }
}
