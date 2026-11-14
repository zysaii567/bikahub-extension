package eu.kanade.tachiyomi.extension.zh.manwa3

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Manwa3 : KeiSource() {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder =
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")

    // ============================= 热门 / 最新 =============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/cate?page=$page".toHttpUrl()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaListPage(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================= 搜索 / 分类 =============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val cleanQuery = query.trim()
        val urlBuilder = if (cleanQuery.isNotEmpty()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("keyword", cleanQuery)
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
        } else {
            var categoryPath = "/cate"
            for (filter in filters) {
                if (filter is CategoryFilter && filter.selectedValue().isNotEmpty()) {
                    categoryPath = filter.selectedValue()
                    break
                }
            }
            "$baseUrl$categoryPath".toHttpUrl().newBuilder().apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return parseMangaListPage(document)
    }

    private fun parseMangaListPage(document: Document): MangasPage {
        val mangaElements = document.select("a[href^=/comic/]:has(.title)")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in mangaElements) {
            val href = el.attr("href").trim()
            if (!href.matches(Regex("""/comic/\d+""")) || !seen.add(href)) continue
            mangas.add(parseMangaFromCard(el))
        }

        val hasNext = document.select("ul.pagination li, div.pagination a, .page-item a").any { it.text().contains("下") }
        return MangasPage(mangas, hasNext)
    }

    private fun parseMangaFromCard(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".title")?.text()?.trim() ?: "未知标题"
        val thumbDiv = element.selectFirst(".thumb_img, .thumbnail img")
        thumbnail_url = thumbDiv?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
    }

    // ============================= 详情 & 章节 =============================

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
                title = document.selectFirst("h1, .comic-info-title, .title")?.text()?.trim() ?: manga.title
                thumbnail_url = document.selectFirst(".comic-info-cover img, .cover img, .thumb_img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
                author = document.select(".author, span:contains(作者) + span, div:contains(作者) span").text().trim().ifEmpty { null }
                artist = author
                genre = document.select(".badge span, .tag, .tags a").eachText().joinToString(", ")
                description = document.selectFirst(".desc, .introduce, .comic-dec")?.text()?.trim()
                status = when {
                    document.text().contains("已完结") || document.text().contains("完结") -> SManga.COMPLETED
                    else -> SManga.ONGOING
                }
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            val elements = document.select("a[href^=/comic/]")
                .filter { it.attr("href").matches(Regex("""/comic/\d+/\d+""")) }
            val seen = mutableSetOf<String>()
            val chs = mutableListOf<SChapter>()

            for (el in elements) {
                val href = el.attr("href").trim()
                if (seen.add(href)) {
                    chs.add(
                        SChapter.create().apply {
                            setUrlWithoutDomain(el.absUrl("href"))
                            name = el.text().trim().ifEmpty { "第${chs.size + 1}话" }
                        },
                    )
                }
            }
            chs.reversed().ifEmpty { chapters }
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    // ============================= 漫画图片列表 =============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("?").substringBefore("_")
        val apiUrl = "$baseUrl/api/comic/image/$chapterId?page=1&page_size=200&image_source=${encodeSource()}"
        val response = client.get(apiUrl, Fetch.XHR)
        val dto = response.parseAs<ImageResponseDto>()

        val images = dto.data?.images?.map { it.url }?.filter { it.isNotEmpty() } ?: emptyList()
        return images.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private fun encodeSource(): String = "https%3A%2F%2F38.246.114.12%3A8067"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/comic/")) return null
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    // ============================= 过滤器 =============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("注意：搜索时分类筛选将被忽略"),
        CategoryFilter(),
    )

    private class CategoryFilter : Filter.Select<String>(
        "分类",
        categoryPairs.map { it.first }.toTypedArray(),
    ) {
        fun selectedValue(): String = categoryPairs[state].second

        companion object {
            private val categoryPairs = arrayOf(
                "全部" to "/cate",
                "后宫" to "/cate/harem",
                "系统" to "/cate/system",
                "韩漫" to "/cate/manhwa",
                "台版" to "/cate/taiwanver",
                "古风" to "/cate/historical",
                "都市" to "/cate/urban",
                "穿越" to "/cate/transmigration",
                "奇幻" to "/cate/fantasy",
                "搞笑" to "/cate/comedy",
                "少男" to "/cate/shounen",
                "动作" to "/cate/action",
                "重生" to "/cate/rebirth",
                "逆袭" to "/cate/counterattack",
            )
        }
    }
}
