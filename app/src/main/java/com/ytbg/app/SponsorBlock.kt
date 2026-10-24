package com.ytbg.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A part of a video to skip: start and end in seconds. */
typealias Segment = Pair<Double, Double>

/**
 * Segments to skip (sponsor reads, intros, talking in music videos...) from the community
 * SponsorBlock database, https://sponsor.ajay.app. Only a 4-character prefix of the video
 * id's hash is sent, so the server does not learn which video is playing.
 */
object SponsorBlock {

    val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    private const val API = "https://sponsor.ajay.app/api/skipSegments/"
    private const val TIMEOUT_MS = 10_000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private val cache = HashMap<String, List<Segment>>()

    /** Looks up the segments of [videoId] and calls [callback] on the main thread. */
    fun segments(videoId: String, categories: Set<String>, callback: (List<Segment>) -> Unit) {
        if (!VIDEO_ID.matches(videoId) || categories.isEmpty()) return
        val key = videoId + categories.sorted().joinToString(",", ":")
        cache[key]?.let {
            callback(it)
            return
        }
        executor.execute {
            val result = fetch(videoId, categories)
            main.post {
                cache[key] = result
                callback(result)
            }
        }
    }

    private fun fetch(videoId: String, categories: Set<String>): List<Segment> = try {
        val prefix = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)
        val categoryParam = URLEncoder.encode(JSONArray(categories.toList()).toString(), "UTF-8")
        val connection = URL("$API$prefix?categories=$categoryParam").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // 404 simply means that no video with this prefix has segments.
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            emptyList()
        } else {
            parse(connection.inputStream.bufferedReader().use { it.readText() }, videoId)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parse(json: String, videoId: String): List<Segment> {
        val videos = JSONArray(json)
        for (i in 0 until videos.length()) {
            val video = videos.getJSONObject(i)
            if (video.optString("videoID") != videoId) continue
            val segments = video.optJSONArray("segments") ?: return emptyList()
            return (0 until segments.length())
                .map { segments.getJSONObject(it) }
                .filter { it.optString("actionType", "skip") == "skip" }
                .mapNotNull { segment ->
                    val range = segment.optJSONArray("segment") ?: return@mapNotNull null
                    Segment(range.optDouble(0), range.optDouble(1))
                }
                .filter { it.first.isFinite() && it.second.isFinite() && it.second > it.first }
                .sortedBy { it.first }
        }
        return emptyList()
    }
}
