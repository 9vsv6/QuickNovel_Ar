package com.lagradost.quicknovel.providers

import android.net.Uri
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
        // Matches the explicit custom library URL path requested
        val url = "$mainUrl/library?type=${mainCategory ?: "0"}&ordering=${orderBy ?: "-num_chapters"}${if (!tag.isNullOrEmpty()) "&genre=$tag" else ""}&page=$page"
        val document = app.get(url).document

        // Target grid blocks visible in image_8cb631.jpg
        val returnValue = document.select("div.row.row--dense > div").mapNotNull { div ->
            val anchorElement = div.selectFirst("a") ?: return@mapNotNull null
            val relativeUrl = anchorElement.attr("href") ?: return@mapNotNull null
            val title = anchorElement.selectFirst("div.v-list-item__title")?.text()?.trim() ?: return@mapNotNull null

            // Direct inline CSS background-image style regex parsing from image_8cb631.jpg
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val infoDiv = document.select("div.container")

        val title = document.selectFirst("h1 > span, div.text-h4")?.text()?.trim() 
            ?: throw ErrorLoadingException("Failed to load novel description metadata")

        // 1. Direct page DOM element map utilizing the structural paths verified in image_8cb254.jpg
        val data: ArrayList<ChapterData> = ArrayList()
        val chapterElements = document.select("div.v-window-item--active a.v-list-item--link, div[role=list] a")
        
        for (ch in chapterElements) {
            val cUrl = ch.attr("href") ?: continue
            
            // Extracts title string block safely out of the list item rows
            val cName = ch.selectFirst("div.v-list-item__title, div.v-list-item__content")?.text()?.trim() 
                ?: ch.text().trim()
                
            data.add(newChapterData(cName, fixUrl(cUrl)))
        }

        // Standard chronological sort normalization check
        data.reverse()

        // 2. Capture cover art from description header styles
        val styleAttr = document.selectFirst("div.v-image__image")?.attr("style") ?: ""
        val coverUrl = Regex("""url\("([^"]+)"\)""").find(styleAttr)?.groupValues?.get(1)
            ?: Regex("""url\(&quot;([^&]+)&quot;\)""").find(styleAttr)?.groupValues?.get(1)

        return newStreamResponse(title, fixUrl(url), data) {
            this.posterUrl = coverUrl?.trim()
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
        val dc = app.get(url).document
        val title = dc.selectFirst("h1, div.v-card__subtitle.font-cairo")?.outerHtml() ?: ""
        val contentElement = getChapterParagraphs(dc) ?: return null
        return title + "</br>" + contentElement.joinToString("</br>")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/novels/search/all/?search=${Uri.encode(query)}"
        val responseDoc = app.get(url).document
        
        // If they return an API layout list response, let's fall back gracefully onto library search routes
        val librarySearchUrl = "$mainUrl/library?search=${Uri.encode(query)}"
        val document = app.get(librarySearchUrl).document

        return document.select("div.row.row--dense > div").mapNotNull { div ->
            val anchorElement = div.selectFirst("a") ?: return@mapNotNull null
            val relativeUrl = anchorElement.attr("href") ?: return@mapNotNull null
            val title = anchorElement.selectFirst("div.v-list-item__title")?.text()?.trim() ?: return@mapNotNull null

            val styleAttr = anchorElement.selectFirst("div.v-image__image")?.attr("style") ?: ""
            val imgUrlMatch = Regex("""url\("([^"]+)"\)""").find(styleAttr)?.groupValues?.get(1)
                ?: Regex("""url\(&quot;([^&]+)&quot;\)""").find(styleAttr)?.groupValues?.get(1)

            newSearchResponse(title, fixUrl(relativeUrl)) {
                posterUrl = imgUrlMatch?.trim()
            }
        }
    }
}
