package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import java.util.*
import kotlin.collections.ArrayList

class CeneleProvider : MainAPI() {
    override val name = "Cenele"
    override val mainUrl = "https://cenele.com"
    override val lang = "ar"
    override val hasMainPage = true
    override val iconId = R.drawable.icon_default 
    override val iconBackgroundId = R.color.readerBackground

    override val tags = listOf(
        "أكشن" to "action",
        "مغامرة" to "adventure",
        "خيال" to "fantasy",
        "دراما" to "drama",
        "رعب" to "horror",
        "رومانسي" to "romantic",
        "غموض" to "mystery",
        "نفسي" to "psychological",
        "خارقة للطبيعة" to "supernatural",
        "كوميديا" to "comedy",
        "فنون قتال" to "martial-arts",
        "حريم" to "harem",
        "شريحة من الحياة" to "slice-of-life",
        "تاريخي" to "historical",
        "علمي" to "sci-fi",
    )

    override val orderBys: List<Pair<String, String>>
        get() = listOf(
            "إفتراضي" to "",
            "A-Z" to "title",
            "Z-A" to "titlereverse",
            "أخر التحديثات" to "update",
            "أخر الإضافات" to "latest",
            "رائج" to "popular",
        )

    override val mainCategories: List<Pair<String, String>>
        get() = listOf(
            "الكل" to "",
            "Ongoing" to "ongoing",
            "Hiatus" to "hiatus",
            "Completed" to "completed",
        )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val url = "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        val returnValue = document.select("div.page-item-detail, div.manga-box").mapNotNull { h ->
            val imageHeader = h.selectFirst("h3.post-title a, div.post-title a") ?: return@mapNotNull null
            val cUrl = imageHeader.attr("href") ?: return@mapNotNull null
            val name = imageHeader.text() ?: return@mapNotNull null
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = h.selectFirst("img")?.attr("src")
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=wp-manga").document
        return document.select("div.c-tabs-item__content, div.row.c-tabs-item__content").mapNotNull { h ->
            val imageHeader = h.selectFirst("div.post-title h3 a, div.post-title a") ?: return@mapNotNull null
            val cUrl = imageHeader.attr("href") ?: return@mapNotNull null
            val name = imageHeader.text() ?: return@mapNotNull null
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = h.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val name = document.select("div.post-title h1").text()
        val authors = document.select("div.author-content a").text()

        // Adaptive endpoint routing for Cenele's URL pattern structure (e.g., /cont/novel-name/)
        val cleanUrl = url.removeSuffix("/")
        val ajaxUrl = "$cleanUrl/ajax/chapters/"
        
        // Fetch dynamic chapter layout via XMLHttpRequest
        val ajaxDocument = app.post(
            ajaxUrl, 
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document

        val data: ArrayList<ChapterData> = ArrayList()
        val chapterHeaders = ajaxDocument.select("li.wp-manga-chapter > a")
        
        for (c in chapterHeaders) {
            val cUrl = c.attr("href") ?: continue
            val cName = c.text().trim()
            val added = c.parent()?.selectFirst("span.chapter-release-date")?.text()?.trim()
            
            data.add(ChapterData(cName, cUrl, added, null))
        }
        
        data.reverse()

        return newStreamResponse(url = url, name = name, data = data) {
            this.author = authors
            this.tags = document.select("div.genres-content a").map { it.text() }
            this.posterUrl = document.select("div.summary_image img").attr("src")
            this.synopsis = document.select("div.summary__content, div.description-summary").text()
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        return document.selectFirst("div.text-left, div.entry-content_wrap, div.reading-content")?.html()
    }
}