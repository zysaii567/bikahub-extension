package eu.kanade.tachiyomi.extension.zh.bikahub

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
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Bikahub : KeiSource() {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder =
        set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .set("Referer", "$baseUrl/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(AesImageInterceptor())

    // ============================= 热门 / 最新 =============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/comic/page/$page".toHttpUrl()
        return parseMangaListPage(url)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================= 搜索 / 分类筛选 =============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val cleanQuery = query.trim()
        val url = if (cleanQuery.isNotEmpty()) {
            "$baseUrl/search/$cleanQuery".toHttpUrl()
        } else {
            var path = "/comic/page/$page"
            for (filter in filters) {
                if (filter is CategoryFilter && filter.selectedValue().isNotEmpty()) {
                    path = filter.selectedValue().replace("searchPage", page.toString())
                    break
                }
            }
            "$baseUrl$path".toHttpUrl()
        }

        return parseMangaListPage(url)
    }

    private suspend fun parseMangaListPage(url: HttpUrl): MangasPage {
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangaElements = document.select("a[href^=/comic/]:has(img)")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in mangaElements) {
            val href = el.attr("href").trim()
            if (!href.matches(Regex("""/comic/\d+"""))) continue
            if (seen.add(href)) {
                mangas.add(parseMangaFromCard(el))
            }
        }

        val hasNextPage = document.select("a[href*=/page/]").any { link ->
            val pageNum = link.attr("href").substringAfterLast("/page/").substringBefore("/").toIntOrNull()
            pageNum != null && mangas.isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaFromCard(element: Element): SManga = SManga.create().apply {
        val absHref = element.absUrl("href")
        setUrlWithoutDomain(absHref)

        val imgEl = element.selectFirst("img")
        val altTitle = imgEl?.attr("alt")?.trim()
        val cardTitle = element.selectFirst("h2, .title")?.text()?.trim()

        title = when {
            !altTitle.isNullOrEmpty() -> altTitle
            !cardTitle.isNullOrEmpty() -> cardTitle
            else -> "未知标题"
        }

        thumbnail_url = imgEl?.let {
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
                title = document.selectFirst("h1")?.attr("title")?.ifEmpty {
                    document.selectFirst("h1")?.text()
                } ?: manga.title

                author = document.select("a[href^=/comic/author/]").text().trim().ifEmpty { null }
                genre = document.select("a.detail-meta-pill, a[href^=/comic/tag/], a[href^=/comic/category/]").joinToString { it.text().trim() }
                description = document.selectFirst("p.whitespace-pre-line, .comic-intro, #intro")?.text()?.trim()

                thumbnail_url = document.selectFirst("img[data-src], .comic-cover img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }

                status = when {
                    document.text().contains("连载中") -> SManga.ONGOING
                    document.text().contains("已完结") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            parseChapterList(document, manga)
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    private fun parseChapterList(document: Document, manga: SManga): List<SChapter> {
        val chapterElements = document.select("section.comic-toc a.comic-toc__item, a[href^=/reader/]")
        if (chapterElements.isEmpty()) {
            val mangaId = manga.url.substringAfterLast("/")
            if (mangaId.isNotEmpty()) {
                return listOf(
                    SChapter.create().apply {
                        name = "第01话"
                        url = "/reader/$mangaId/1"
                    },
                )
            }
            return emptyList()
        }

        val seen = mutableSetOf<String>()
        val chapters = mutableListOf<SChapter>()

        for (el in chapterElements) {
            val href = el.attr("href").trim()
            if (href.isEmpty() || !seen.add(href)) continue

            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain(el.absUrl("href"))
                    name = el.text().trim().ifEmpty { "开始阅读" }
                },
            )
        }

        return chapters
    }

    // ============================= 漫画图片列表 =============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter), Fetch.DOCUMENT)
        val html = response.body.string()

        val unescaped = html.replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        val regex = Regex("""https://pic\.drleao\.cn/[^"'\s]+auth_key=[^"'\s]+""")
        val imageUrls = regex.findAll(unescaped)
            .map { it.value }
            .distinct()
            .toList()

        if (imageUrls.isEmpty()) {
            val doc = org.jsoup.Jsoup.parse(html)
            val domImages = doc.select("img[data-src*='pic.drleao.cn'], img[src*='pic.drleao.cn']")
                .map { it.attr("data-src").ifEmpty { it.attr("src") } }
                .filter { it.isNotEmpty() }
                .distinct()

            return domImages.mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
        }

        return imageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/comic/")) return null
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    // ============================= 过滤器 =============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("注意：文本搜索时分类筛选将被忽略"),
        CategoryFilter(),
    )

    private class CategoryFilter :
        Filter.Select<String>(
            "分类 / 标签",
            categoryPairs.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = categoryPairs[state].second

        companion object {
            private val categoryPairs = arrayOf(
                "全部漫画" to "/comic/page/searchPage",
                "日漫" to "/comic/category/riman/page/searchPage",
                "同人" to "/comic/category/tongren/page/searchPage",
                "韩漫" to "/comic/category/hanman/page/searchPage",
                "制服" to "/comic/tag/zhifu/page/searchPage",
                "巨乳" to "/comic/tag/juru/page/searchPage",
                "JK" to "/comic/tag/jk/page/searchPage",
                "人妻" to "/comic/tag/renqi/page/searchPage",
                "全彩" to "/comic/tag/quancai/page/searchPage",
                "动漫视频" to "/anime/page/searchPage",
                "AI短剧" to "/drama/page/searchPage",
            )
        }
    }
}

