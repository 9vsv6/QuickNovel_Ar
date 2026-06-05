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
    override val iconId = R.drawable.icon_default // Adjusted fallback to default resource template layer
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
        // Formulate the requested search library path explicitly matching your endpoint description
        val url = "$mainUrl/library?type=${mainCategory ?: "0"}&ordering=${orderBy ?: "-num_chapters"}${if (!tag.isNullOrEmpty()) "&genre=$tag" else ""}&page=$page"
        val document = app.get(url).document

        // Target the inner structural wrapping grid nodes shown in your DevTools image snapshot
        val returnValue = document.select("div.row.row--dense > div").mapNotNull { div ->
            val anchorElement = div.selectFirst("a") ?: return@mapNotNull null
            val relativeUrl = anchorElement.attr("href") ?: return@mapNotNull null
            
            // Extract the true textual title heading from the correct metadata block element
            val title = anchorElement.selectFirst("div.v-list-item__title")?.text()?.trim() ?: return@mapNotNull null

            // Direct inline CSS background-image style extraction parsing fix verified from image_8cc178.jpg
            val styleAttr = anchorElement.selectFirst("div.v-image__image")?.attr("style") ?: ""
            val imgUrlMatch = Regex("""url\("([^"]+)"\)""").find(styleAttr)?.groupValues?.get(1)
                ?: Regex("""url\(&quot;([^&]+)&quot;\)""").find(styleAttr)?.groupValues?.get(1)

            newSearchResponse(
                name = title,
                url = fixUrl(relativeUrl)
            ) {
                this.posterUrl = imgUrlMatch?.trim()
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    private suspend fun getChapters(document: Document, url: String): List<ChapterData> {
        val novelSlug = url.substringAfterLast("/")
        val newUrl = "$secondUrl/api/chapters/$novelSlug/?ordering=number&page="
        
        return try {
            val chapter = app.get(newUrl + "1").parsed<RewayatMainResponse>()
            val totalChapters = chapter.count
            if (totalChapters > 0) {
                (0 until totalChapters.toInt()).map { chapterNumber ->
                    val chapterUrl = "$newUrl-------$chapterNumber-------$totalChapters"
                    newChapterData("الفصل ${chapterNumber + 1}", chapterUrl)
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // Document element parsing fallback loop routine if api endpoint fails
            document.select("div[role=list] > div > a, div.v-window-item a").mapIndexedNotNull { index, li ->
                val name = li.selectFirst("div.v-list-item__content")?.text() ?: "الفصل $index"
                val cUrl = fixUrl(li.attr("href"))
                newChapterData(name, cUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val infoDiv = document.select("div.container")

        val title = document.selectFirst("h1 > span, div.text-h4")?.text()?.trim() 
            ?: throw ErrorLoadingException("Failed to capture title nodes")

        val chapters = getChapters(document, url)
        
        // Grab direct poster layout node maps
        val styleAttr = document.selectFirst("div.v-image__image")?.attr("style") ?: ""
        val coverUrl = Regex("""url\("([^"]+)"\)""").find(styleAttr)?.groupValues?.get(1)
            ?: Regex("""url\(&quot;([^&]+)&quot;\)""").find(styleAttr)?.groupValues?.get(1)

        return newStreamResponse(title, fixUrl(url), chapters) {
            this.posterUrl = coverUrl
            this.synopsis = document.selectFirst("div.text-body-2.text-pre-line, div.font-cairo.mb-6")?.text()?.trim() ?: ""
        }
    }

    private fun getChapterParagraphs(doc: Document): List<String>? {
        val script = doc.select("script")
            .firstOrNull { it.data().contains("window.__NUXT__") }
            ?.data()
        val contentRaw = Regex("""\.content\s*=\s*"(.*?)";""")
            .find(script ?: "")
            ?.groupValues?.get(1)
        
        val html = contentRaw
            ?.replace("\\n", "")
            ?.replace("\\u003C", "<")
            ?.replace("\\u003E", ">")
            ?.replace("\\u002F", "/")
            ?: return null

        return Jsoup.parse(html)
            .select("p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
    }

    override suspend fun loadHtml(url: String): String? {
        val chapterData = url.split("-------")
        if (chapterData.size == 1) {
            val dc = app.get(url).document
            val title = dc.selectFirst("div.v-card__subtitle.font-cairo")?.outerHtml() ?: ""
            val contentElement = getChapterParagraphs(dc) ?: return null
            return title + contentElement.joinToString("</br>")
        }

        val baseUrl = chapterData[0]
        val chapterBigIndex = chapterData[1].toInt()
        val totalChapters = chapterData[2].toInt()
        val itemsPerPage = 24

        val remainder = totalChapters % itemsPerPage
        val offset = if (remainder == 0) 0 else itemsPerPage - remainder
        val adjustedIndex = chapterBigIndex + offset

        val normalPage = if (remainder > chapterBigIndex) (adjustedIndex / itemsPerPage) + 1 else (chapterBigIndex / itemsPerPage) + 1
        val chapterIndex = chapterBigIndex % itemsPerPage

        val response = app.get("$baseUrl$normalPage").parsed<RewayatMainResponse>()
        val chapter = response.results.getOrNull(chapterIndex) ?: return null
        
        val slug = url.substringAfterLast("chapters/").substringBefore("/?ordering")
        val fullUrl = "$mainUrl/novel/$slug/${chapter.number}"
        
        val dc = app.get(fullUrl).document
        val title = dc.selectFirst("h1 a, div.text-h5")?.outerHtml() ?: ""
        val contentElement = getChapterParagraphs(dc) ?: return null
        return title + "</br>" + contentElement.joinToString("</br>")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/novels/search/all/?search=${Uri.encode(query)}"
        val document = app.get(url).parsed<RewayatSearchResponse>()
        return document.results.map { element ->
            val title = element.english.ifBlank { element.arabic }
            val novelUrl = fixUrl("/novel/" + element.slug)
            val coverUrl = if (element.posterUrl.startsWith("http")) element.posterUrl else secondUrl + element.posterUrl
            newSearchResponse(title, novelUrl) {
                posterUrl = coverUrl
            }
        }
    }

    data class RewayatSearchResponse(
        val count: Long,
        val next: String?,
        val previous: Any?,
        val results: List<Result>,
    )

    data class Result(
        val arabic: String,
        val english: String,
        val slug: String,
        @JsonProperty("poster_url")
        val posterUrl: String,
        @JsonProperty("num_chapters")
        val numChapters: Long,
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
        val title: String,
    )
}
