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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
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
            // Decrypt images in mangas
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

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/rank", Fetch.DOCUMENT).asJsoup()
        val mangas = document.select("#rankList_2 > a").map { element ->
            SManga.create().apply {
                title = element.attr("title")
                url = element.attr("href")
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-original")
            }
        }
        return MangasPage(mangas, false)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/rank", Fetch.DOCUMENT).asJsoup()
        val mangas = document.select("#rankList_1 > a").map { element ->
            SManga.create().apply {
                title = element.attr("title")
                url = element.attr("href")
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-original")
            }
        }
        return MangasPage(mangas, false)
    }

    // Search
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        var isBookList = false
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addQueryParameter("keyword", query)
            } else {
                isBookList = true
                encodedPath("/booklist")
                filters.forEach { filter ->
                    when (filter) {
                        is UriPartFilter -> filter.setParamPair(this)
                        is TagCheckBoxFilterGroup -> filter.setParamPair(this)
                        else -> {}
                    }
                }
            }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = if (isBookList) {
            document.select("ul.manga-list-2 > li").map { li ->
                SManga.create().apply {
                    title = li.selectFirst("p.manga-list-2-title")?.text() ?: ""
                    setUrlWithoutDomain(li.selectFirst("a")!!.absUrl("href"))
                    thumbnail_url = li.selectFirst("img")?.attr("abs:src")
                }
            }
        } else {
            document.select("ul.book-list > li").map { li ->
                SManga.create().apply {
                    title = li.selectFirst("p.book-list-info-title")?.text() ?: ""
                    setUrlWithoutDomain(li.selectFirst("a")!!.absUrl("href"))
                    thumbnail_url = li.selectFirst("img")?.attr("abs:data-original")
                }
            }
        }
        val next = document.select("ul.pagination2 > li").lastOrNull()?.text() == "下一页"

        return MangasPage(mangas, next)
    }

    // Details & Chapters
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val details = SManga.create().apply {
            url = manga.url
            title = document.selectFirst(".detail-main-info-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.detail-main-cover > img")?.attr("abs:data-original")
            author = document.select("p.detail-main-info-author > span.detail-main-info-value > a").text()
            artist = author
            status = when (document.select("p.detail-main-info-author:contains(更新状态) > span.detail-main-info-value").text()) {
                "连载中" -> SManga.ONGOING
                "已完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = document.select("div.detail-main-info-class > a.info-tag").eachText().joinToString(", ")
            description = document.selectFirst("#detail > p.detail-desc")?.text()
        }
        val chapterList = document.select("ul#detail-list-select > li > a").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = element.text()
            }
        }.reversed()
        // The site only shows the last update date of the whole manga, so it's put on the newest chapter
        chapterList.firstOrNull()?.date_upload = DATE_FORMAT.tryParseDate(
            document.selectFirst(".detail-list-title-3")?.text()?.substringBefore("更新"),
        )
        return SMangaUpdate(details, chapterList)
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}", Fetch.DOCUMENT).asJsoup()
        return document.select("#cp_img > div.img-content > img[data-r-src]").mapIndexed { index, it ->
            Page(index, imageUrl = it.attr("abs:data-r-src"))
        }
    }

    // Filters
    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/booklist", Fetch.DOCUMENT).asJsoup()
        val tags = LinkedHashMap<String, String>()
        document.select("div.manga-filter-row.tags > a").forEach { a ->
            tags[a.text()] = a.attr("data-val")
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
