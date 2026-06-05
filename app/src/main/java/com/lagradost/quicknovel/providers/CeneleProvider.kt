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
        
        // Target custom child-theme grids derived from image_8d311e.jpg DOM elements
        val returnValue = document.select("article.nhv-nrRow, div.nhv-pitem, div.page-item-detail").mapNotNull { h ->
            // Precise selector pairing to lock onto correct title nodes
            val textLink = h.selectFirst("a.nhv-nrTitle, h4.nhv-pTitle, h3.post-title a, div.post-title a") ?: return@mapNotNull null
            val cUrl = textLink.attr("href") ?: return@mapNotNull null
            val name = textLink.text().trim()
            
            if (name.isBlank() || name.startsWith("image-") || name.startsWith("peak")) return@mapNotNull null
            
            val imgElement = h.selectFirst("img")
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src")
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=wp-manga").document
        return document.select("article.nhv-nrRow, div.nhv-pitem, div.page-item-detail, div.c-tabs-item__content").mapNotNull { h ->
            val textLink = h.selectFirst("a.nhv-nrTitle, h4.nhv-pTitle, div.post-title h3 a, div.post-title a, div.item-thumb a") ?: return@mapNotNull null
            val cUrl = textLink.attr("href") ?: return@mapNotNull null
            val name = textLink.text().trim()
            
            if (name.isBlank() || name.startsWith("image-") || name.startsWith("peak")) return@mapNotNull null
            
            val imgElement = h.selectFirst("img")
            
            newSearchResponse(name = name, url = cUrl) {
                posterUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val mainTitle = document.select("div.manga-title h2").text().trim()
        val altTitle = document.select("div.manga-alt-title").text().replace("رواية", "").trim()
        val finalName = if (altTitle.isNotBlank()) "$mainTitle ($altTitle)" else mainTitle
        val authors = document.select("div.manga-author a, div.author-content a").text().trim()

        val chaptersContainer = document.selectFirst("div#nhv-manga-chapters")
        val novelId = chaptersContainer?.attr("data-novel") 
            ?: Regex("""\"manga_id\":\"(\d+)\"""").find(document.html())?.groupValues?.get(1) 
            ?: ""

        val data: ArrayList<ChapterData> = ArrayList()

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
            this.synopsis = document.select("div.excerpt-content, div.summary__content, div.description-summary").text().trim()
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        val contentSelector = document.selectFirst("div.read-container, div.text-left, div.reading-content") ?: return null
        
        contentSelector.select("div.orw-ad-slot, div.orw-reader-gap").remove()
        contentSelector.select("[style*=opacity:0], [style*=transparent], [aria-hidden=true]").remove()
        
        val cleanHtml = StringBuilder()
        val paragraphs = contentSelector.select("p")
        
        for (p in paragraphs) {
            var text = p.text().trim()
            if (text.isBlank()) continue

            val isEntirelyFake = text.contains("فضاء الروايات") && text.contains("مسروق")
            if (isEntirelyFake) continue

            val fakePatterns = listOf(
                Regex("""هذا نص تمويهي من موقع.*?(?=(\s[أإا]ذا|http|$))"""),
                Regex("""[أإا]ذا ظهر داخل تطبيق آخر فالإ?مصر مسروق.*?(?=(http|$))"""),
                Regex("""اقرأ من المصدر.*?(?=(http|$))"""),
                Regex("""شاي روايات تطبيق سارق ويأخذ محتوى بدون إذن.*?(?=(http|$))"""),
                Regex("""https://cenele.com/#[A-Za-z0-9_*/]+"""),
                Regex("""9865dfg #[A-Za-z0-9_*\/]+""")
            )

            for (pattern in fakePatterns) {
                text = text.replace(pattern, "")
            }

            text = text.replace(Regex("""\s*,\s*"""), " ").trim()

            if (text.isNotBlank() && text.length > 5) {
                cleanHtml.append("<p>").append(text).append("</p>")
            }
        }

        return cleanHtml.toString()
    }
}
