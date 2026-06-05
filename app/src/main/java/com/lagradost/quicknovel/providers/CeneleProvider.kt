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
        
        val mainTitle = document.select("div.manga-title h2").text().trim()
        val altTitle = document.select("div.manga-alt-title").text().replace("رواية", "").trim()
        val finalName = if (altTitle.isNotBlank()) "$mainTitle ($altTitle)" else mainTitle
        val authors = document.select("div.manga-author a, div.author-content a").text().trim()

        // 1. Grab the dynamic novel database details configured inside the parent container attributes
        // Seen in your screenshot elements: data-novel="1234" or data-id
        val chaptersContainer = document.selectFirst("div#nhv-manga-chapters")
        val novelId = chaptersContainer?.attr("data-novel") 
            ?: Regex("""\"manga_id\":\"(\d+)\"""").find(document.html())?.groupValues?.get(1) 
            ?: ""

        val data: ArrayList<ChapterData> = ArrayList()

        if (novelId.isNotBlank()) {
            // 2. Query the native async core endpoint directly using a simulated AJAX form payload
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            
            val ajaxResponse = app.post(
                ajaxUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url
                ),
                data = mapOf(
                    "action" to "nhv_manga_get_chapters", // Custom child theme hook handler
                    "manga" to novelId
                )
            ).document

            // 3. Drill down into the injected accordion panels once fully rendered by the server response
            val volumeSections = ajaxResponse.select("section.nhv-volume-card")
            
            for (volume in volumeSections) {
                val chapters = volume.select("li.wp-manga-chapter")
                for (ch in chapters) {
                    val linkElement = ch.selectFirst("a") ?: continue
                    val cUrl = linkElement.attr("href") ?: continue
                    
                    val spanName = linkElement.selectFirst("span.nhv-chapter-name")?.text()?.trim()
                    val cName = if (!spanName.isNullOrBlank()) spanName else linkElement.text().trim()
                    val added = ch.selectFirst("span.chapter-release-date")?.text()?.trim()
                    
                    data.add(ChapterData(cName, cUrl, added, null))
                }
            }
        }

        // Final fallback block: If the custom script pipeline blocks us, query the standard routing endpoint
        if (data.isEmpty()) {
            val cleanUrl = url.removeSuffix("/")
            val fallbackDoc = app.post(
                "$cleanUrl/ajax/chapters/", 
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document
            
            fallbackDoc.select("li.wp-manga-chapter > a, div.nhv-latest10-bridge__grid a").forEach { c ->
                data.add(ChapterData(c.text().trim(), c.attr("href"), null, null))
            }
        }

        // Sort chronologically ensuring index safety tracking
        data.distinctBy { it.url }
        if (data.size > 1) {
            val firstNum = data.first().name.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val lastNum = data.last().name.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            if (firstNum > lastNum) {
                data.reverse()
            }
        }

        return newStreamResponse(url = url, name = finalName, data = data) {
            this.author = authors.ifBlank { "Unknown" }
            this.tags = document.select("div.genres-content a").map { it.text().trim() }
            this.posterUrl = document.select("div.summary_image img").attr("src")
            this.synopsis = document.select("div.summary__content, div.description-summary").text().trim()
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        return document.selectFirst("div.text-left, div.entry-content_wrap, div.reading-content")?.html()
    }
}
