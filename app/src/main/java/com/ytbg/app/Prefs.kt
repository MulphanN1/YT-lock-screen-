package com.ytbg.app

import android.content.Context

object Prefs {
    private const val FILE = "ytbg"
    private const val KEY_ENABLED = "background_enabled"
    private const val KEY_AD_BLOCK = "ad_block"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isAdBlockEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AD_BLOCK, true)

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AD_BLOCK, enabled)
            .apply()
    }
}
