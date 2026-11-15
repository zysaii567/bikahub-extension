package eu.kanade.tachiyomi.extension.zh.sansiyule

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Sansiyule : KeiSource() {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder =
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")

    override fun getImageRequest(page: Page): Request =
        super.getImageRequest(page).newBuilder()
            .header("Referer", "$baseUrl/")
            .build()

    // 区分当前源实例是“连载”还是“完结”
    private val isOngoing: Boolean
        get() = name.contains("连载")

    private val defaultCategoryPath: String
        get() = if (isOngoing) "/item/%e8%bf%9e%e8%bd%bd" else "/item/%e5%ae%8c%e7%bb%93"

    // ============================= 热门 / 最新 =============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val path = if (page > 1) "$defaultCategoryPath/page/$page" else defaultCategoryPath
        return parseMangaListPage("$baseUrl$path".toHttpUrl())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================= 搜索 / 分类筛选 =============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val cleanQuery = query.trim()
        val url = if (cleanQuery.isNotEmpty()) {
            val path = if (page > 1) "/page/$page" else ""
            "$baseUrl$path".toHttpUrl().newBuilder()
                .addQueryParameter("s", cleanQuery)
                .build()
        } else {
            var selectedPath = defaultCategoryPath
            for (filter in filters) {
                if (filter is CategoryFilter && filter.selectedValue().isNotEmpty()) {
                    selectedPath = filter.selectedValue()
                    break
                }
            }
            val path = if (page > 1) "$selectedPath/page/$page" else selectedPath
            "$baseUrl$path".toHttpUrl()
        }

        return parseMangaListPage(url)
    }

    private suspend fun parseMangaListPage(url: HttpUrl): MangasPage {
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val elements = document.select("article.excerpt")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in elements) {
            val a = el.selectFirst("h2 a") ?: el.selectFirst("a.thumbnail") ?: continue
            val href = a.attr("href").trim()
            if (href.isEmpty() || !seen.add(href)) continue

            mangas.add(parseMangaFromCard(el, a))
        }

        val hasNextPage = document.selectFirst("div.pagination li.next-page a") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaFromCard(element: Element, linkEl: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(linkEl.absUrl("href"))

        val titleText = linkEl.text().trim()
        val titleAttr = linkEl.attr("title").removeSuffix("-三四娱乐").trim()
        val imgEl = element.selectFirst("p.focus img, img")

        title = when {
            titleText.isNotEmpty() -> titleText
            titleAttr.isNotEmpty() -> titleAttr
            else -> imgEl?.attr("alt")?.removeSuffix("封面")?.trim() ?: "未知标题"
        }

        thumbnail_url = imgEl?.let {
            val dataSrc = it.attr("data-src").ifEmpty { it.attr("data-original") }
            val src = it.attr("src")
            when {
                dataSrc.isNotEmpty() && !dataSrc.contains("placeholder") -> dataSrc
                src.isNotEmpty() && !src.contains("placeholder") -> src
                else -> dataSrc.ifEmpty { src }
            }
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

                val titleEl = document.selectFirst("h1.article-title a, h1.article-title")
                title = titleEl?.ownText()?.trim()?.ifEmpty {
                    titleEl.text().substringBefore("第").substringBefore("-三四娱乐").trim()
                } ?: manga.title

                val imgEl = document.selectFirst(".c-img img, p.focus img, article.article-content img")
                thumbnail_url = imgEl?.let {
                    val dataSrc = it.attr("data-src").ifEmpty { it.attr("data-original") }
                    val src = it.attr("src")
                    when {
                        dataSrc.isNotEmpty() && !dataSrc.contains("placeholder") -> dataSrc
                        src.isNotEmpty() && !src.contains("placeholder") -> src
                        else -> dataSrc.ifEmpty { src }
                    }
                } ?: manga.thumbnail_url

                description = document.selectFirst("span.dis, .article-content p.note")?.text()?.removePrefix("==>")?.trim()

                val genres = mutableListOf<String>()
                document.select(".breadcrumbs a, .article-tags a, ul.article-meta a[rel='category tag']").forEach { tagEl ->
                    val text = tagEl.text().trim()
                    if (text.isNotEmpty() && text != "正文" && text != "首页") {
                        genres.add(text)
                    }
                }
                genre = genres.distinct().joinToString()

                val pageText = document.text()
                status = when {
                    pageText.contains("已完结") || pageText.contains("完结") -> SManga.COMPLETED
                    pageText.contains("连载中") || pageText.contains("连载") -> SManga.ONGOING
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
        val chapterElements = document.select(".chapter-navigation a.chapter-link")
        if (chapterElements.isEmpty()) {
            val hasImages = document.select("article.article-content img[data-src], article.article-content img[src]").any {
                val src = it.attr("data-src").ifEmpty { it.attr("src") }
                src.isNotEmpty() && !src.contains("placeholder")
            }
            if (hasImages) {
                return listOf(
                    SChapter.create().apply {
                        name = "全一话"
                        url = manga.url
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

        val reversedChapters = chapters.reversed()

        val dateText = document.select("ul.article-meta li").firstOrNull { it.text().contains("最后修改:") }
            ?.text()?.substringAfter("最后修改:")?.trim()
        val dateUpload = dateText?.let { DATE_FORMAT.tryParseDate(it) } ?: 0L
        if (dateUpload > 0L) {
            reversedChapters.firstOrNull()?.date_upload = dateUpload
        }

        return reversedChapters
    }

    // ============================= 漫画图片列表 =============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val imgElements = document.select("article.article-content img")

        val imageUrls = mutableListOf<String>()
        for (img in imgElements) {
            val dataSrc = img.attr("data-src").ifEmpty { img.attr("data-original") }
            val src = img.attr("src")
            val targetUrl = when {
                dataSrc.isNotEmpty() && !dataSrc.contains("placeholder") -> dataSrc
                src.isNotEmpty() && !src.contains("placeholder") -> src
                else -> ""
            }

            if (targetUrl.isNotEmpty()) {
                val absUrl = if (targetUrl.startsWith("http")) targetUrl else img.absUrl("data-src").ifEmpty { img.absUrl("src") }
                if (absUrl.isNotEmpty() && !imageUrls.contains(absUrl)) {
                    imageUrls.add(absUrl)
                }
            }
        }

        return imageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/post/")) return null
        val cleanPath = if (path.matches(Regex("""/post/\d+/page-\d+\.html"""))) {
            val postId = path.substringAfter("/post/").substringBefore("/")
            "/post/$postId.html"
        } else {
            path
        }
        return SManga.create().apply {
            setUrlWithoutDomain(cleanPath)
        }
    }

    // ============================= 过滤器 =============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("注意：文本搜索时分类筛选将被忽略"),
        CategoryFilter(isOngoing),
    )

    private class CategoryFilter(isOngoing: Boolean) :
        Filter.Select<String>(
            "分类 / 标签",
            categoryPairs.map { it.first }.toTypedArray(),
            if (isOngoing) 0 else 1,
        ) {
        fun selectedValue(): String = categoryPairs[state].second

        companion object {
            private val categoryPairs = arrayOf(
                "连载漫画" to "/item/%e8%bf%9e%e8%bd%bd",
                "完结漫画" to "/item/%e5%ae%8c%e7%bb%93",
                "精品漫画" to "/item/%e7%b2%be%e5%93%81%e6%bc%ab%e7%94%bb",
                "都市" to "/item/%e9%83%bd%e5%b8%82",
                "爱情漫画" to "/mh",
                "短篇漫画" to "/item/%e7%9f%ad%e7%af%87%e6%bc%ab%e7%94%bb",
                "美女写真" to "/meinv",
            )
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    }
}
