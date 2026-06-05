package com.lagradost.quicknovel.providers

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class RewayatProvider(): MainAPI() {
    override val name = "Rewayat"
    override val mainUrl = "https://rewayat.club"
    private val secondUrl = "https://api.rewayat.club"
    override val iconId = R.drawable.icon_default

    override val hasMainPage = true
    override val iconBackgroundId = R.color.readerBackground
    override val lang = "ar"

    override val orderBys = listOf(
        "عدد الفصول - من أقل لأعلى" to "num_chapters",
        "عدد الفصول - من أعلى لأقل" to "-num_chapters",
        "الاسم - من أقل لأعلى" to "english",
        "الاسم - من أعلى لأقل" to "-english"
    )
    override val mainCategories = listOf(
        "جميع الروايات" to "0",
        "مترجمة" to "1",
        "مؤلفة" to "2",
        "مكتملة" to "3"
    )
    override val tags = listOf(
        "All" to "",
        "كوميديا" to "1",
        "أكشن" to "2",
        "دراما" to "3",
        "فانتازيا" to "4",
        "مهارات القتال" to "5",
        "مغامرة" to "6",
        "رومانسي" to "7",
        "خيال علمي" to "8",
        "الحياة المدرسية" to "9",
        "قوى خارقة" to "10",
        "سحر" to "11",
        "رياضة" to "12",
        "رعب" to "13",
        "حريم" to "14"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val typeParam = mainCategory ?: "0"
        val orderParam = orderBy ?: "-num_chapters"
        val genreParam = if (!tag.isNullOrEmpty()) "&genre=$tag" else ""
        
        // Bypasses the HTML frame and queries the backend REST endpoints directly
        val apiUrl = "$secondUrl/api/novels/?type=$typeParam&ordering=$orderParam&page=$page$genreParam"
        val response = app.get(apiUrl).parsed<RewayatSearchResponse>()

        val returnValue = response.results.map { element ->
            val title = element.arabic?.ifBlank { element.english }.orEmpty().ifBlank { "Novel" }
            val novelUrl = "$mainUrl/novel/${element.slug}"
            val coverUrl = if (element.posterUrl?.startsWith("http") == true) {
                element.posterUrl
            } else {
                "$secondUrl${element.posterUrl.orEmpty()}"
            }
            
            newSearchResponse(name = title, url = novelUrl) {
                this.posterUrl = coverUrl
            }
        }
        
        val pageUrl = "$mainUrl/library?type=$typeParam&ordering=$orderParam$genreParam&page=$page"
        return HeadMainPageResponse(pageUrl, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$secondUrl/api/novels/?search=${Uri.encode(query)}"
        val response = app.get(apiUrl).parsed<RewayatSearchResponse>()
        
        return response.results.map { element ->
            val title = element.arabic?.ifBlank { element.english }.orEmpty().ifBlank { "Novel" }
            val novelUrl = "$mainUrl/novel/${element.slug}"
            val coverUrl = if (element.posterUrl?.startsWith("http") == true) {
                element.posterUrl
            } else {
                "$secondUrl${element.posterUrl.orEmpty()}"
            }
            
            newSearchResponse(name = title, url = novelUrl) {
                this.posterUrl = coverUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.removeSuffix("/").substringAfterLast("/")
        val detailUrl = "$secondUrl/api/novels/$slug/"
        
        // Fetches novel descriptions safely from the native core model layout parameters
        val detail = try {
            app.get(detailUrl).parsed<RewayatNovelDetail>()
        } catch (e: Exception) {
            val fallbackUrl = "$secondUrl/api/novels/?search=$slug"
            val searchRes = app.get(fallbackUrl).parsed<RewayatSearchResponse>()
            val match = searchRes.results.firstOrNull { it.slug == slug }
            if (match != null) {
                RewayatNovelDetail(match.id, match.arabic, match.english, match.slug, match.posterUrl, "", match.numChapters ?: 0)
            } else {
                throw ErrorLoadingException("Failed to pull novel parameters from API")
            }
        }

        val title = detail.arabic?.ifBlank { detail.english }.orEmpty().ifBlank { "Novel" }
        val coverUrl = if (detail.posterUrl?.startsWith("http") == true) {
            detail.posterUrl
        } else {
            "$secondUrl${detail.posterUrl.orEmpty()}"
        }

        // Lazy pagination indexing routine mapping full chapter data bundles sequentially
        val chaptersUrl = "$secondUrl/api/chapters/$slug/?ordering=number&page="
        val firstPageResponse = app.get(chaptersUrl + "1").parsed<RewayatMainResponse>()
        val totalChapters = firstPageResponse.count

        val chapterList = ArrayList<ChapterData>()
        if (totalChapters > 0) {
            for (i in 0 until totalChapters.toInt()) {
                val chapterUrl = "$chaptersUrl-------$i-------$totalChapters"
                chapterList.add(newChapterData("الفصل ${i + 1}", chapterUrl))
            }
        }

        return newStreamResponse(title, fixUrl(url), chapterList) {
            this.posterUrl = coverUrl
            this.synopsis = detail.description.orEmpty().trim()
        }
    }

    private fun getChapterParagraphs(doc: Document): List<String> {
        val htmlParagraphs = doc.select("div.chapter-content p, div.text-body-1 p, p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            
        if (htmlParagraphs.size > 3) return htmlParagraphs

        // Fallback: Parse window.__NUXT__ structure tokens if text content is string-hydrated
        val scriptData = doc.select("script").map { it.data() }.firstOrNull { it.contains("window.__NUXT__") } ?: ""
        val contentPattern = Regex("""["']content["']\s*:\s*"([^"]+)"""")
        val contentRaw = contentPattern.find(scriptData)?.groupValues?.get(1)
            ?: Regex("""\.content\s*=\s*"(.*?)";""").find(scriptData)?.groupValues?.get(1)

        if (contentRaw != null) {
            val html = contentRaw
                .replace("\\n", "")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u002F", "/")
            return Jsoup.parse(html).select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        }
        
        return htmlParagraphs
    }

    override suspend fun loadHtml(url: String): String? {
        val chapterData = url.split("-------")
        if (chapterData.size == 1) {
            val dc = app.get(url).document
            val title = dc.selectFirst("div.v-card__subtitle.font-cairo, h1")?.outerHtml() ?: ""
            val contentElement = getChapterParagraphs(dc)
            return title + contentElement.joinToString("</br>")
        }

        val baseUrl = chapterData[0]
        val chapterBigIndex = chapterData[1].toInt()
        val itemsPerPage = 24

        val normalPage = (chapterBigIndex / itemsPerPage) + 1
        val chapterIndex = chapterBigIndex % itemsPerPage

        val response = app.get("$baseUrl$normalPage").parsed<RewayatMainResponse>()
        val chapter = response.results.getOrNull(chapterIndex) ?: return null
        
        val slug = url.substringAfterLast("chapters/").substringBefore("/?ordering")
        val fullUrl = "$mainUrl/novel/$slug/${chapter.number}"
        
        val dc = app.get(fullUrl).document
        val title = dc.selectFirst("h1 a, div.text-h5, h1")?.outerHtml() ?: ""
        val contentElement = getChapterParagraphs(dc)
        return title + "</br>" + contentElement.joinToString("</br>")
    }

    // JSON Model Framework mapping properties safely via Jackson deserializers
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RewayatNovelDetail(
        val id: Long,
        val arabic: String?,
        val english: String?,
        val slug: String,
        @JsonProperty("poster_url")
        val posterUrl: String?,
        val description: String?,
        @JsonProperty("num_chapters")
        val numChapters: Long
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RewayatSearchResponse(
        val count: Long,
        val results: List<Result>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val arabic: String?,
        val english: String?,
        val slug: String,
        @JsonProperty("poster_url")
        val posterUrl: String?,
        @JsonProperty("num_chapters")
        val numChapters: Long?,
        val id: Long,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RewayatMainResponse(
        val count: Long,
        val results: List<Result2>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result2(
        val number: Long,
        val title: String?,
    )
}
