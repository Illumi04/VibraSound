package com.example.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SimpleDownloader private constructor() : Downloader() {

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val instance = SimpleDownloader()
        fun getInstance(): SimpleDownloader = instance
    }

    // CookieJar persistente en memoria para compartir cookies entre peticiones y redirecciones
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.topPrivateDomain() ?: url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(existing) {
                for (cookie in cookies) {
                    existing.removeAll { it.name == cookie.name }
                    existing.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.topPrivateDomain() ?: url.host
            val list = mutableListOf<Cookie>()
            cookieStore[host]?.let {
                synchronized(it) {
                    list.addAll(it)
                }
            }
            return list
        }
    }

    // OkHttpClient con followRedirects(true), followSslRedirects(true) y cabeceras de navegador
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "es-ES,es;q=0.9")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")

            // Inyectar forzosamente la cookie de consentimiento
            val existingCookie = original.header("Cookie")
            val consentCookie = "CONSENT=YES+cb; GPS=1; SOCS=CAESEwgDEgk1ODE2Mjg5NDQaAmVuIAEaBgiA_LyaBg; PREF=hl=es&gl=ES"
            val combinedCookie = if (existingCookie.isNullOrBlank()) {
                consentCookie
            } else if (!existingCookie.contains("CONSENT=")) {
                "$existingCookie; $consentCookie"
            } else {
                existingCookie
            }
            requestBuilder.header("Cookie", combinedCookie)

            chain.proceed(requestBuilder.build())
        })
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder().url(url)

        if (headers != null) {
            for ((key, values) in headers) {
                if (values.isNotEmpty()) {
                    reqBuilder.header(key, values.joinToString(", "))
                }
            }
        }

        val requestBody = if (dataToSend != null && dataToSend.isNotEmpty()) {
            dataToSend.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        } else if (httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        reqBuilder.method(httpMethod, requestBody)

        val okResponse = client.newCall(reqBuilder.build()).execute()
        val responseCode = okResponse.code
        val responseMessage = okResponse.message
        val responseHeaders = okResponse.headers.toMultimap()
        val latestUrl = okResponse.request.url.toString()
        val responseBody = okResponse.body?.string() ?: ""

        return Response(responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
    }
}

object MediaExtractor {

    init {
        // Inicializar NewPipe con el SimpleDownloader basado en OkHttp
        NewPipe.init(SimpleDownloader.getInstance())
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val searchExtractor: SearchExtractor = ServiceList.YouTube.getSearchExtractor(query)
        searchExtractor.fetchPage()
        val page = searchExtractor.initialPage
        val results = mutableListOf<SearchResult>()

        for (item in page.items) {
            if (item is StreamInfoItem) {
                val thumbUrl = item.thumbnails?.lastOrNull()?.url
                    ?: item.thumbnails?.firstOrNull()?.url
                    ?: ""
                results.add(
                    SearchResult(
                        title = item.name ?: "",
                        artist = item.uploaderName ?: "",
                        url = item.url ?: "",
                        durationSeconds = item.duration,
                        thumbnailUrl = thumbUrl
                    )
                )
            }
        }
        results
    }

    suspend fun getAudioStreamUrl(videoUrl: String): String? = withContext(Dispatchers.IO) {
        val cleanUrl = when {
            videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> videoUrl
            else -> "https://www.youtube.com/watch?v=$videoUrl"
        }
        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, cleanUrl)
        val audioStreams: List<AudioStream> = streamInfo.audioStreams ?: emptyList()

        val bestAudioStream = audioStreams.maxByOrNull { it.averageBitrate }
            ?: audioStreams.maxByOrNull { it.bitrate }

        bestAudioStream?.url
    }
}
