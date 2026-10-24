package com.ytbg.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Network-level blocking of ad and ad-tracking requests made by the YouTube page. */
object AdBlock {

    private val AD_HOSTS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
    )

    private val YOUTUBE_AD_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/get_midroll_info",
    )

    fun isAd(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = uri.path ?: return false
        val youtube = host == "youtube.com" || host.endsWith(".youtube.com")
        return youtube && YOUTUBE_AD_PATHS.any { path.startsWith(it) }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
