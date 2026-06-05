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
        val url = "$mainUrl/cont/page/$page/"
        val document = app.get(url).document
        
        val returnValue = document.select("div#loop-content div.page-item-detail").mapNotNull { h ->
            val imageHeader = h.selectFirst("div.item-thumb a") ?: return@mapNotNull null
            val cUrl = imageHeader.attr("href") ?: return@mapNotNull null
            val name = imageHeader.attr("title") ?: return@mapNotNull null
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = imageHeader.selectFirst("img")?.attr("data-src") 
                    ?: imageHeader.selectFirst("img")?.attr("src")
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=wp-manga").document
        return document.select("div#loop-content div.page-item-detail, div.c-tabs-item__content").mapNotNull { h ->
            val imageHeader = h.selectFirst("div.item-thumb a, div.post-title a") ?: return@mapNotNull null
            val cUrl = imageHeader.attr("href") ?: return@mapNotNull null
            val name = imageHeader.attr("title")?.ifBlank { imageHeader.text() } ?: return@mapNotNull null
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = h.selectFirst("img")?.attr("data-src") 
                    ?: h.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val name = document.select("div.post-title h1").text()
        val authors = document.select("div.author-content a").text()

        val data: ArrayList<ChapterData> = ArrayList()
        
        // Target custom child-theme grid items directly from the main document body
        val chapterHeaders = document.select("div.nhv-latest10-bridge__grid a.nhv-latest10-bridge__item")
        
        for (c in chapterHeaders) {
            val cUrl = c.attr("href") ?: continue
            val cName = c.text().trim() 
            val added = null 
            
            data.add(ChapterData(cName, cUrl, added, null))
        }
        
        // Chronological sort inversion
        data.reverse()

        // Structural backwards fallback safety routine
        if (data.isEmpty()) {
            val cleanUrl = url.removeSuffix("/")
            val ajaxDocument = app.post(
                "$cleanUrl/ajax/chapters/", 
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document
            
            ajaxDocument.select("li.wp-manga-chapter > a").forEach { c ->
                data.add(ChapterData(c.text().trim(), c.attr("href"), null, null))
            }
            data.reverse()
        }

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
