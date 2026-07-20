package com.wdtt.client

import android.content.Context

object AdminSession {
    private const val PREFS = "admin_session"
    private const val KEY_TOKEN = "jwt"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun getToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return !getToken(context).isNullOrBlank()
    }
}