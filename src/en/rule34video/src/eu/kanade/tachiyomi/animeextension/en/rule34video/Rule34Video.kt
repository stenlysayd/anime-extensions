package eu.kanade.tachiyomi.animeextension.en.rule34video
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Rule34Video :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.e("Rule34Video", "Loading popular videos from uploader ID: $uploaderId, page: $page, URL: $url")
            GET(url)
        } else {
            Log.e("Rule34Video", "Uploader filter enabled but ID is blank, loading latest updates.")
            GET("$baseUrl/latest-updates/$page/")
        }
    } else {
        GET("$baseUrl/latest-updates/$page/")
    }

    // The site now injects ad cards ("div.item.thumb" whose link points to an external
    // ad network) between the real results, so only keep cards linking to /video/<id>/.
    override fun popularAnimeSelector() = "div.item.thumb:has(a.th[href*=\"/video/\"])"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.th")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.attr("title").ifBlank { link.selectFirst("div.thumb_title")?.text().orEmpty() }
        thumbnail_url = link.selectFirst("div.img img")?.let { img ->
            // "data-original" lazy loading was removed; the real URL is now in "src".
            img.absUrl("src").ifBlank { img.absUrl("data-rd-jpg") }.ifBlank { img.absUrl("data-original") }
        }
    }

    // New pager: <nav class="fs-pager"><a rel="next">; the legacy markup is kept as a fallback.
    override fun popularAnimeNextPageSelector() = "nav.fs-pager a[rel=next], div.item.pager.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() = (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val slug = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$slug", filters)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private inline fun <reified R : AnimeFilter.Text> AnimeFilterList.getText() = (find { it is R } as? AnimeFilter.Text)?.state?.trim().orEmpty()

    private fun String.toSlug() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // OrderFilter values are the site's own "sort_by" values (verified from the sort menu).
        val sortType = filters.getUriPart<OrderFilter>()
        val typeId = filters.getUriPart<CategoryBy>()

        if (query.startsWith(PREFIX_SEARCH)) {
            val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
            return GET("$baseUrl/search/$newQuery")
        }

        // Tag / Artist / Category pages: https://rule34video.com/{tags|models|categories}/<slug>/<page>/
        val browse = listOf(
            "tags" to filters.getText<TagFilter>(),
            "models" to filters.getText<ArtistFilter>(),
            "categories" to filters.getText<CategoryNameFilter>(),
        ).firstOrNull { it.second.isNotBlank() }

        val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                browse != null -> {
                    addPathSegment(browse.first)
                    addPathSegment(browse.second.toSlug())
                    if (page > 1) addPathSegment(page.toString())
                    addPathSegment("")
                }

                // Text search is path based: /search/<query>/<page>/
                query.isNotBlank() -> {
                    addPathSegment("search")
                    addPathSegment(query.trim().replace(Regex("\\s+"), "-"))
                    if (page > 1) addPathSegment(page.toString())
                    addPathSegment("")
                }

                // Type only: /search/?...&from_videos=<page> (the list's page variable is "from_videos")
                typeId.isNotEmpty() -> {
                    addPathSegment("search")
                    addPathSegment("")
                    addQueryParameter("from_videos", page.toString())
                }

                else -> {
                    addPathSegment("latest-updates")
                    if (page > 1) addPathSegment(page.toString())
                    addPathSegment("")
                }
            }
            if (sortType.isNotEmpty()) addQueryParameter("sort_by", sortType)
            // Legacy type parameter, still unverified on the redesigned site.
            if (typeId.isNotEmpty() && browse == null) addQueryParameter("flag1", typeId)
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")?.text().orEmpty()

        val detailRows = document.select("div.row")
        val meta = document.select("div.vp-meta span.item_info > span").eachText()

        author = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name")
            .firstOrNull()?.text().orEmpty()

        description = buildString {
            document.selectFirst("div.vp-desc")?.text()?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append("\n\n")
            }

            meta.getOrNull(0)?.let { append("Uploaded: $it\n") }

            val artist = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name")
                .eachText()
                .joinToString()
            if (artist.isNotEmpty()) append("Artists: $artist\n")

            val categories = detailRows.select("div.col:has(div.label:contains(Categories)) a.item span")
                .eachText()
                .joinToString()
            if (categories.isNotEmpty()) append("Categories: $categories\n")

            val uploader = detailRows.select("div.col:has(div.label:contains(Uploaded by)) a.item").text()
            if (uploader.isNotEmpty()) append("Uploader: $uploader\n")

            meta.getOrNull(1)?.let { append("Views: $it\n") }
            meta.getOrNull(2)?.let { append("Duration: $it\n") }

            val qualities = document.select(DOWNLOAD_LINK_SELECTOR)
                .mapNotNull { QUALITY_REGEX.find(it.text())?.value }
                .joinToString()
            if (qualities.isNotEmpty()) append("Quality: $qualities")
        }.trim()

        genre = document.select("div.vp-tags a.tag_item")
            .eachText()
            .joinToString()

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Video"
        },
    )

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val headers = headersBuilder()
            .apply {
                val cookies = client.cookieJar.loadForRequest(response.request.url)
                    .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
                    .map { "${it.name}=${it.value}" }
                    .joinToString("; ")
                val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")
                xsrfToken?.let { add("X-XSRF-TOKEN", it) }
                add("Cookie", cookies)
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Referer", response.request.url.toString())
                add("Accept-Language", "en-US,en;q=0.5")
            }.build()

        val document = response.asJsoup()

        // 1) The player's own sources (flashvars in an inline script): video_url, video_alt_url, video_alt_url2...
        // 2) Fallback: the links inside the new "Download" sheet.
        val sources = parseFlashvarsSources(document).ifEmpty { parseDownloadSheetSources(document) }

        return sources.mapNotNull { (quality, originalUrl) ->
            // The file server returns a http 403 when the player (http/1.1) connects to the
            // tokenised url directly, so resolve the redirect target with OkHttp (http/2) first.
            val url = resolveRedirect(originalUrl, headers)
            Video(url, quality, url, headers)
        }
    }

    private fun parseFlashvarsSources(document: Document): List<Pair<String, String>> {
        val script = document.select("script")
            .map { it.data() }
            .firstOrNull { "flashvars" in it && "video_url" in it }
            ?: return emptyList()

        return FLASHVARS_URL_REGEX.findAll(script).mapNotNull { match ->
            val key = match.groupValues[1]
            val url = match.groupValues[2].replace("\\/", "/")
            val quality = Regex("""${key}_text:\s*'([^']+)'""").find(script)?.groupValues?.get(1)
                ?: QUALITY_REGEX.find(url)?.value
                ?: return@mapNotNull null
            quality to url
        }.toList()
    }

    private fun parseDownloadSheetSources(document: Document): List<Pair<String, String>> = document.select(DOWNLOAD_LINK_SELECTOR).mapNotNull { element ->
        val quality = QUALITY_REGEX.find(element.text())?.value ?: return@mapNotNull null
        val href = element.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Drop the "force download" parameters so the file can be streamed.
        val url = href.toHttpUrl().newBuilder()
            .removeAllQueryParameters("download")
            .removeAllQueryParameters("download_filename")
            .build()
            .toString()
        quality to url
    }

    private fun resolveRedirect(url: String, headers: okhttp3.Headers): String = runCatching {
        noRedirectClient.newCall(GET(url, headers)).execute().use { res ->
            if (res.isRedirect) {
                res.header("location")?.let { res.request.url.resolve(it)?.toString() } ?: url
            } else {
                url
            }
        }
    }.getOrDefault(url)

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: return this
        return sortedWith(compareByDescending { it.videoTitle == quality })
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPLOADER_FILTER_ENABLED_KEY
            title = "Filter by Uploader"
            summary = "Load videos only from the specified uploader ID."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_UPLOADER_ID_KEY
            title = "Uploader ID"
            summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
            dialogTitle = "Enter Uploader ID"
            setOnPreferenceChangeListener { _, newValue ->
                newValue?.toString().isNullOrBlank().not()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = entries
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
        preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
    ) {
        AnimeFilterList() // If uploader filter is enabled and ID is set, show no other filters
    } else {
        AnimeFilterList(
            OrderFilter(),
            CategoryBy(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Browse by name (ignores the search text, first filled field wins):"),
            AnimeFilter.Header("Type it as shown on the site, e.g. \"kanae katagiri (bleach)\""),
            TagFilter(),
            ArtistFilter(),
            CategoryNameFilter(),
        )
    }

    private class TagFilter : AnimeFilter.Text("Tag", "")

    private class ArtistFilter : AnimeFilter.Text("Artist", "")

    private class CategoryNameFilter : AnimeFilter.Text("Category (series/game)", "")

    private class CategoryBy :
        UriPartFilter(
            "Type ",
            arrayOf(
                Pair("All", ""),
                Pair("Straight", "2109"),
                Pair("Futa", "15"),
                Pair("Gay", "192"),
                Pair("Music", "4747"),
                Pair("Iwara", "1821"),
            ),
        )

    private class OrderFilter :
        UriPartFilter(
            "Sort By ",
            arrayOf(
                Pair("Default (Newest / Relevance)", ""),
                Pair("Newest", "post_date"),
                Pair("Most Viewed", "video_viewed"),
                Pair("Top Rated", "rating"),
                Pair("Longest", "duration"),
                Pair("Random", "pseudo_rand"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val DOWNLOAD_LINK_SELECTOR = "div.vp-sheet[data-sheet=download] a.tag_item_download"
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
        private val FLASHVARS_URL_REGEX = Regex("""(video(?:_alt)?_url\d*):\s*'(https?:[^']+)'""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
