package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import java.util.*
import kotlin.collections.ArrayList

class ArNoProvider : MainAPI() {
    override val name = "ArNo"
    override val mainUrl = "https://ar-no.com"
    override val lang = "ar"
    override val hasMainPage = true
    override val iconId = R.drawable.icon_default // Change to your local drawable icon if you have one

    // If the theme color is known, you can set it here, otherwise use a default fallback
    override val iconBackgroundId = R.color.readerBackground 

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        // Madara theme typical pagination
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

        // 1. Grab the novel's distinct URL identifier to construct the AJAX endpoint
        // e.g., "https://ar-no.com/novel/reverend-insanity/" -> "reverend-insanity"
        val slug = url.removeSuffix("/").split("/").last()
        
        // 2. Fetch the dynamic chapter HTML snippet directly using the network endpoint you caught
        val ajaxUrl = "$mainUrl/novel/$slug/chapters/?t=${System.currentTimeMillis()}"
        val ajaxDocument = app.get(ajaxUrl).document

        val data: ArrayList<ChapterData> = ArrayList()
        
        // 3. Select the elements using the specific class names verified from your network inspector snippet
        val chapterHeaders = ajaxDocument.select("li.wp-manga-chapter > a")
        
        for (c in chapterHeaders) {
            val cUrl = c.attr("href") ?: continue
            val cName = c.text().trim()
            // Madara lists dates inside <span> tags next to titles or inside <i> elements
            val added = c.parent()?.selectFirst("span.chapter-release-date, i")?.text() 
            
            data.add(ChapterData(cName, cUrl, added, null))
        }
        
        // Madara standard sets the newest chapters at the top. Reverse it so reading order is chronological.
        data.reverse()

        return newStreamResponse(url = url, name = name, data = data) {
            author = authors
            tags = document.select("div.genres-content a").map { it.text() }
            posterUrl = document.select("div.summary_image img").attr("src")
            synopsis = document.select("div.summary__content, div.description-summary").text()
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        // Targets the content body layout inside the reading interface framework
        return document.selectFirst("div.text-left, div.entry-content_wrap, div.reading-content")?.html()
    }
}