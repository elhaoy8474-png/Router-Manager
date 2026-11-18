package com.example.routermanager.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the router address and credentials. Only written when the user ticks "Remember me".
 * Values are encrypted with an AES-256 key held in the Android Keystore.
 */
class CredentialStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Saved(val host: String, val username: String, val password: String)

    fun save(saved: Saved) {
        prefs.edit()
            .putString(KEY_HOST, saved.host)
            .putString(KEY_USER, saved.username)
            .putString(KEY_PASS, saved.password)
            .apply()
    }

    fun load(): Saved? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return Saved(host, user, pass)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "router_credentials"
        const val KEY_HOST = "host"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
