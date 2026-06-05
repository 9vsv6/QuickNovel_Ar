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
        
        // Maps the catalog card containers visible in the layout tree
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
        
        // Combined extraction parsing for localized and clean global titles
        val mainTitle = document.select("div.manga-title h2").text().trim()
        val altTitle = document.select("div.manga-alt-title").text().replace("رواية", "").trim()
        val finalName = if (altTitle.isNotBlank()) "$mainTitle ($altTitle)" else mainTitle
        val authors = document.select("div.manga-author a, div.author-content a").text().trim()

        // 1. Parse the core database novel identifier from the DOM structure container
        val chaptersContainer = document.selectFirst("div#nhv-manga-chapters")
        val novelId = chaptersContainer?.attr("data-novel") 
            ?: Regex("""\"manga_id\":\"(\d+)\"""").find(document.html())?.groupValues?.get(1) 
            ?: ""

        val data: ArrayList<ChapterData> = ArrayList()

        // 2. Query the native async endpoint directly using a simulated AJAX form payload
        if (novelId.isNotBlank()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            
            val ajaxResponse = app.post(
                ajaxUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url
                ),
                data = mapOf(
                    "action" to "nhv_manga_get_chapters", 
                    "manga" to novelId
                )
            ).document

            // 3. Loop sequentially through the nested volume layers injected inside the response layout
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

        // Structural backward fallback safety check loop
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

        // Filter duplicates and verify chronological alignment formatting configuration arrays
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
        
        // 1. Target the correct readable core text container block layout node wrapper
        val contentSelector = document.selectFirst("div.read-container, div.text-left, div.reading-content") ?: return null
        
        // 2. Clear out standard advertisement blocks and tracking components explicitly
        contentSelector.select("div.orw-ad-slot").remove()     
        contentSelector.select("div.orw-reader-gap").remove()   
        contentSelector.select("[style*=opacity:0]").remove()    
        contentSelector.select("[style*=transparent]").remove()  
        contentSelector.select("[aria-hidden=true]").remove()    
        
        // 3. Deep-cleaning textual filtration loop targeting cloaked anti-theft content markers
        val paragraphs = contentSelector.select("p")
        for (p in paragraphs) {
            val text = p.text().trim()
            
            // Evaluates targeted strings against known rotating anti-bot warning keywords and unique layout hashes
            val isFakeText = text.contains("فضاء الروايات") || 
                             text.contains("مسروق") || 
                             text.contains("تطبيق") || 
                             text.contains("تمويهي") || 
                             text.contains("المصدر الوحيد") ||
                             text.contains("بدون إذن") ||
                             text.contains("cenele.com") ||
                             text.contains("#") // Wipes text paragraphs carrying embedded tracking hash keys
            
            if (isFakeText) {
                p.remove() // Instantly deletes the toxic tag node out of the document collection
            }
        }

        return contentSelector.html()
    }
}
