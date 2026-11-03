package com.ytbg.app

import android.content.Context
import android.content.SharedPreferences

/** App settings. The keys are shared with res/xml/settings.xml. */
object Prefs {
    const val FILE = "ytbg"

    private const val KEY_ENABLED = "background_enabled"
    const val KEY_AD_BLOCK = "ad_block"
    const val KEY_AMBIENT = "ambient"
    const val KEY_AMBIENT_STRENGTH = "ambient_strength"
    const val KEY_LOW_QUALITY = "low_quality_background"
    const val KEY_RESUME = "resume_playback"
    const val KEY_SPONSOR_BLOCK = "sponsorblock"
    const val KEY_SPONSOR_CATEGORIES = "sponsorblock_categories"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_POSITION = "last_position_ms"

    /** Keep in sync with the defaultValue in settings.xml. */
    val DEFAULT_SPONSOR_CATEGORIES = setOf("sponsor", "selfpromo", "interaction", "music_offtopic")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAdBlockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AD_BLOCK, true)

    fun isAmbientEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMBIENT, true)

    /** 0..1; the default must match settings.xml. */
    fun ambientStrength(context: Context): Float = prefs(context).getInt(KEY_AMBIENT_STRENGTH, 55) / 100f

    fun isLowQualityInBackground(context: Context): Boolean = prefs(context).getBoolean(KEY_LOW_QUALITY, true)

    fun isResumeEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME, true)

    fun isSponsorBlockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SPONSOR_BLOCK, true)

    fun sponsorCategories(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SPONSOR_CATEGORIES, DEFAULT_SPONSOR_CATEGORIES) ?: DEFAULT_SPONSOR_CATEGORIES

    /** Where to continue after the app was closed: the watch page and the position in it. */
    fun saveLastPlayback(context: Context, url: String, positionMs: Long) {
        prefs(context).edit()
            .putString(KEY_LAST_URL, url)
            .putLong(KEY_LAST_POSITION, positionMs)
            .apply()
    }

    fun lastPlaybackUrl(context: Context): String? = prefs(context).getString(KEY_LAST_URL, null)

    fun lastPlaybackPosition(context: Context): Long = prefs(context).getLong(KEY_LAST_POSITION, 0)
}
