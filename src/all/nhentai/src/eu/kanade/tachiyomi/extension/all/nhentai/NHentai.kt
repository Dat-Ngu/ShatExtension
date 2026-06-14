package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : ConfigurableSource, ParsedHttpSource() {

    final override val baseUrl = "https://nhentai.net"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            // nhentai resets HTTP/2 image streams ("stream was reset: PROTOCOL_ERROR") — pin HTTP/1.1.
            .protocols(listOf(Protocol.HTTP_1_1))
            // Retry transient failures (429s, dropped image-CDN connections). forcedServer pins
            // images to one mirror per the "Image server" preference (default Server 1).
            .addInterceptor(RetryInterceptor(forcedServer = { imageServer }))
            .rateLimit(3)
            .build()
    }

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    // "auto" = use the per-page server nhentai assigns; "1".."4" = force that image server.
    // nhentai mirrors every page across i1–i4, so forcing the one your network can reach
    // (e.g. some networks/VPNs only reach i1) fixes "connection reset" on the others.
    private val imageServer: String
        get() = preferences.getString(SERVER_PREF, "1") ?: "1"

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = "Image server"
            entries = arrayOf("Auto (load-balanced)", "Server 1", "Server 2", "Server 3", "Server 4")
            entryValues = arrayOf("auto", "1", "2", "3", "4")
            summary = "Force a specific nhentai image server (i1–i4). Use this if your network/VPN " +
                "can only reach some servers — every page is mirrored on all of them, and the app " +
                "still auto-falls-back to the others on failure. Current: %s"
            setDefaultValue("1")
        }.also(screen::addPreference)
    }

    override fun latestUpdatesRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page", headers)

    override fun latestUpdatesSelector() = "#content .container:not(.index-popular) .gallery"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a > div").text().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    override fun latestUpdatesNextPageSelector() = "#content > section.pagination > a.next"

    override fun popularMangaRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page" else "$baseUrl/language/$nhLang/popular?page=$page", headers)

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val id = query.removePrefix(PREFIX_ID_SEARCH)
                client.newCall(searchMangaByIdRequest(id))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, id) }
            }
            query.toIntOrNull() != null -> {
                client.newCall(searchMangaByIdRequest(query))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, query) }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$baseUrl/favorites/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", offsetPage.toString())

            return GET(url.build(), headers)
        } else {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                // Blank query (Multi + sort by popular month/week/day) shows a 404 page
                // Searching for `""` is a hacky way to return everything without any filtering
                .addQueryParameter("q", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())

            filterList.findInstance<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            return GET(url.build(), headers)
        }
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val y = !(filter.name == "Pages" || filter.name == "Uploaded")
                    if (tag.startsWith("-")) append("-")
                    append(filter.name, ':')
                    if (y) append('"')
                    append(tag.removePrefix("-"))
                    if (y) append('"')
                    append(" ")
                }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id/", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response.asJsoup())
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("/login/")) {
            val document = response.asJsoup()
            if (document.select(".fa-sign-in").isNotEmpty()) {
                throw Exception("Log in via WebView to view favorites")
            }
        }

        return super.searchMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ===================== Gallery (scraped from /g/{id}/ HTML) =====================
    // nhentai removed the inline `window._gallery = JSON.parse(...)` blob from /g/ pages,
    // and its /api/gallery/{id} endpoint is Cloudflare-blocked harder than the page itself.
    // So we scrape the rendered gallery DOM — the only endpoint confirmed reachable — and
    // derive each page's image URL straight from its thumbnail. Tags are typed by their
    // href (/artist/, /group/, /tag/, …) which is far more stable than the English labels.

    private fun Document.tagsByType(): Map<String, List<String>> =
        select("#tags .tag").groupBy(
            { it.attr("href").trim('/').substringBefore('/') },
            { it.selectFirst(".name")?.text().orEmpty().trim() },
        ).mapValues { entry -> entry.value.filter { it.isNotBlank() } }

    override fun mangaDetailsParse(document: Document): SManga {
        if (document.selectFirst("#info") == null) document.assertGalleryLoaded()

        val tags = document.tagsByType()
        val englishTitle = document.selectFirst("#info h1")?.text().orEmpty()
        val japaneseTitle = document.selectFirst("#info h2")?.text().orEmpty()
        val prettyTitle = document.selectFirst("#info h1 .pretty")?.text()?.takeIf { it.isNotBlank() }
            ?: englishTitle.ifBlank { japaneseTitle }
        val artists = tags["artist"].orEmpty()
        val groups = tags["group"].orEmpty()
        val pageCount = document.select("#thumbnail-container .thumb-container").size
        val favorites = document.selectFirst("#favorite")?.text()
            ?.let { Regex("""[\d,]+""").find(it)?.value?.replace(",", "") }
            .orEmpty()

        return SManga.create().apply {
            title = if (displayFullTitle) {
                englishTitle.ifBlank { japaneseTitle }
            } else {
                prettyTitle.shortenTitle()
            }
            thumbnail_url = document.selectFirst("#cover img")?.imageAttr()
            status = SManga.COMPLETED
            artist = artists.joinToString(", ")
            author = groups.joinToString(", ").ifBlank { artists.joinToString(", ") }
            description = buildString {
                append("Full English and Japanese titles:\n")
                append(englishTitle.ifBlank { japaneseTitle }).append("\n")
                append(japaneseTitle).append("\n\n")
                if (pageCount > 0) append("Pages: ").append(pageCount).append("\n")
                if (favorites.isNotBlank()) append("Favorited by: ").append(favorites).append("\n")
                tags["category"]?.takeIf { it.isNotEmpty() }
                    ?.let { append("Categories: ").append(it.joinToString(", ")).append("\n") }
                tags["parody"]?.takeIf { it.isNotEmpty() }
                    ?.let { append("Parodies: ").append(it.joinToString(", ")).append("\n") }
                tags["character"]?.takeIf { it.isNotEmpty() }
                    ?.let { append("Characters: ").append(it.joinToString(", ")).append("\n") }
            }
            genre = tags["tag"].orEmpty().joinToString(", ")
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        if (document.selectFirst("#info") == null) document.assertGalleryLoaded()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = document.tagsByType()["group"].orEmpty().joinToString(", ").ifBlank { null }
                date_upload = document.selectFirst("time[datetime]")?.attr("datetime").parseUploadDate()
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun pageListParse(document: Document): List<Page> {
        val thumbnails = document.select("#thumbnail-container .thumb-container img")
        if (thumbnails.isEmpty()) document.assertGalleryLoaded()
        val server = imageServer
        return thumbnails.mapIndexed { i, img ->
            // Thumbnail: https://t3.nhentai.net/galleries/{media_id}/{n}t.webp
            // Image:     https://i3.nhentai.net/galleries/{media_id}/{n}.webp
            // nhentai sometimes serves a DOUBLED extension (…/{n}t.webp.webp); the trailing
            // `(?:\.\w+)*` collapses it so we still produce a clean …/{n}.webp full-image URL
            // (a malformed `{n}t.webp.webp` URL gets connection-reset by the image server).
            var imageUrl = img.imageAttr()
                .replaceFirst(Regex("""//t(\d*)\.nhentai\.net"""), "//i$1.nhentai.net")
                .replaceFirst(Regex("""/(\d+)t\.(\w+)(?:\.\w+)*(?:\?.*)?$"""), "/$1.$2")
            if (server != "auto") {
                // Pin to the chosen mirror; RetryInterceptor still rotates if it ever fails.
                imageUrl = imageUrl.replaceFirst(Regex("""//i\d*\.nhentai\.net"""), "//i$server.nhentai.net")
            }
            Page(i, imageUrl = imageUrl)
        }
    }

    private fun Element.imageAttr(): String =
        if (hasAttr("data-src")) attr("abs:data-src") else attr("abs:src")

    private fun String?.parseUploadDate(): Long {
        if (this.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(substringBefore(".").substringBefore("+").trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // Distinguish a Cloudflare block from a genuine page-format change, for a clear error.
    private fun Document.assertGalleryLoaded(): Nothing {
        val pageTitle = title()
        val looksCloudflare = listOf("just a moment", "attention required", "cloudflare", "blocked")
            .any { pageTitle.contains(it, ignoreCase = true) } ||
            body()?.text().orEmpty().contains("you have been blocked", ignoreCase = true)
        throw Exception(
            if (looksCloudflare) {
                "Cloudflare is still blocking the app's request. Open this source in WebView to solve it, then retry."
            } else {
                "nhentai gallery data not found — the page format may have changed. Page title: \"$pageTitle\""
            },
        )
    }

    // ParsedHttpSource hooks unused by this source
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()
    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Popular: All Time", "popular"),
            Pair("Popular: Month", "popular-month"),
            Pair("Popular: Week", "popular-week"),
            Pair("Popular: Today", "popular-today"),
            Pair("Recent", "date"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    // Retries transient failures on idempotent GETs.
    //  • Non-image requests: retry 429 (honouring Retry-After) and connection drops.
    //  • Image/thumbnail requests (i{n}/t{n}.nhentai.net): nhentai mirrors images across servers
    //    1–4, but some are unreachable/flaky from certain networks (e.g. VPN exits). On any
    //    failure we re-fetch the SAME path from another server, preferring whichever last worked,
    //    and fully buffer the body so a mid-stream "unexpected end of stream" also rotates rather
    //    than surfacing as a dead reader panel.
    private class RetryInterceptor(
        private val forcedServer: () -> String,
        private val maxRetries: Int = 3,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val match = IMAGE_HOST.matchEntire(request.url.host)
            return if (match != null) {
                interceptImage(chain, request, match.groupValues[1], match.groupValues[2])
            } else {
                interceptDefault(chain, request)
            }
        }

        private fun interceptImage(
            chain: Interceptor.Chain,
            request: Request,
            prefix: String,
            originalServer: String,
        ): Response {
            // Hard-pinned to one mirror: rewrite the host to it (correcting even a stale cached
            // URL that points at another server) and retry ONLY that host — never fall back to
            // servers the user's network can't reach.
            val forced = forcedServer()
            if (forced != "auto") {
                val pinnedReq = request.newBuilder()
                    .url(request.url.newBuilder().host("$prefix$forced.nhentai.net").build())
                    .build()
                var attempt = 0
                while (true) {
                    try {
                        val response = chain.proceed(pinnedReq)
                        if (response.code == 429 && attempt < maxRetries) {
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            response.close()
                            attempt++
                            sleepQuietly((retryAfter?.times(1000L) ?: 1500L).coerceAtMost(10_000L))
                            continue
                        }
                        if (!response.isSuccessful) return response
                        val body = response.body ?: return response
                        val contentType = body.contentType()
                        val bytes = body.bytes()
                        return response.newBuilder()
                            .body(bytes.toResponseBody(contentType))
                            .build()
                    } catch (e: IOException) {
                        if (attempt >= maxRetries) throw e
                        attempt++
                        sleepQuietly(1000L * attempt)
                    }
                }
            }

            val servers = buildList {
                preferredServer?.let { add(it) }
                if (originalServer.isNotEmpty()) add(originalServer)
                addAll(SERVERS)
            }.distinct()

            var lastError: IOException? = null
            var lastResponse: Response? = null
            for (server in servers) {
                val url = request.url.newBuilder().host("$prefix$server.nhentai.net").build()
                try {
                    val response = chain.proceed(request.newBuilder().url(url).build())
                    if (!response.isSuccessful) {
                        lastResponse?.close()
                        lastResponse = response
                        if (response.code == 429) sleepQuietly(1500L)
                        continue
                    }
                    val body = response.body ?: return response
                    val contentType = body.contentType()
                    val bytes = body.bytes() // throws on a mid-stream cutoff → rotates to next server
                    preferredServer = server
                    lastResponse?.close()
                    return response.newBuilder()
                        .body(bytes.toResponseBody(contentType))
                        .build()
                } catch (e: IOException) {
                    lastError = e
                }
            }
            lastResponse?.let { return it }
            throw lastError ?: IOException("All nhentai image servers failed: ${request.url}")
        }

        private fun interceptDefault(chain: Interceptor.Chain, request: Request): Response {
            var attempt = 0
            while (true) {
                try {
                    val response = chain.proceed(request)
                    if (response.code == 429 && attempt < maxRetries) {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        response.close()
                        attempt++
                        sleepQuietly((retryAfter?.times(1000L) ?: 1500L).coerceAtMost(10_000L))
                        continue
                    }
                    return response
                } catch (e: IOException) {
                    if (request.method != "GET" || attempt >= maxRetries) throw e
                    attempt++
                    sleepQuietly(1000L * attempt)
                }
            }
        }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted during retry backoff", e)
            }
        }

        companion object {
            private val IMAGE_HOST = Regex("""([it])(\d*)\.nhentai\.net""")
            private val SERVERS = listOf("1", "2", "3", "4")

            // Remembers the last image server that worked, so after the first success we hit it
            // first instead of re-failing the user's unreachable servers on every page.
            @Volatile
            private var preferredServer: String? = null
        }
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
        private const val SERVER_PREF = "image_server"
    }
}
