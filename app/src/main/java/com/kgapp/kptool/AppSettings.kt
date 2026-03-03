package com.kgapp.kptool

import android.content.Context

object AppSettings {
    private const val PREFS = "kptool_prefs"
    private const val KEY_DETAILED_LOGS = "detailed_logs_enabled"

    fun isDetailedLogsEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DETAILED_LOGS, false)
    }

    fun setDetailedLogsEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DETAILED_LOGS, enabled).apply()
    }
}
