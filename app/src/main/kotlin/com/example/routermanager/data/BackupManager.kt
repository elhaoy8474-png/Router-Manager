package com.example.routermanager.data

import android.content.Context
import android.net.Uri
import com.example.routermanager.ui.AppSettings
import org.json.JSONObject

/**
 * مسؤول عن تحويل الإعدادات إلى JSON والعكس،
 * وقراءة/كتابة الملفات عبر (SAF) في أندرويد.
 */
object BackupManager {

    fun exportToJson(settings: AppSettings, nicknames: Map<String, String>): String {
        val root = JSONObject()
        
        // 1. حفظ الإعدادات
        val settingsJson = JSONObject()
        settingsJson.put("theme", settings.theme)
        settingsJson.put("language", settings.language)
        settingsJson.put("autoRefresh", settings.autoRefresh)
        settingsJson.put("autoRefreshInterval", settings.autoRefreshInterval)
        settingsJson.put("notificationsEnabled", settings.notificationsEnabled)
        settingsJson.put("notifyNewDevice", settings.notifyNewDevice)
        settingsJson.put("notifyDeviceBlocked", settings.notifyDeviceBlocked)
        settingsJson.put("showOfflineDevices", settings.showOfflineDevices)
        settingsJson.put("compactMode", settings.compactMode)
        root.put("settings", settingsJson)

        // 2. حفظ أسماء الأجهزة المخصصة
        val nicknamesJson = JSONObject()
        nicknames.forEach { (mac, name) -> nicknamesJson.put(mac, name) }
        root.put("nicknames", nicknamesJson)

        return root.toString(2) // 2 = مسافات للقراءة البشرية
    }

    fun importFromJson(jsonString: String): Pair<AppSettings, Map<String, String>>? {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("settings") && !root.has("nicknames")) return null
            if (root.has("settings") && root.optJSONObject("settings") == null) return null
            if (root.has("nicknames") && root.optJSONObject("nicknames") == null) return null

            val settingsJson = root.optJSONObject("settings")
            val settings = if (settingsJson != null) {
                AppSettings(
                    theme = settingsJson.optString("theme", "system"),
                    language = settingsJson.optString("language", "ar"),
                    autoRefresh = settingsJson.optBoolean("autoRefresh", true),
                    autoRefreshInterval = settingsJson.optInt("autoRefreshInterval", 30),
                    notificationsEnabled = settingsJson.optBoolean("notificationsEnabled", true),
                    notifyNewDevice = settingsJson.optBoolean("notifyNewDevice", true),
                    notifyDeviceBlocked = settingsJson.optBoolean("notifyDeviceBlocked", true),
                    showOfflineDevices = settingsJson.optBoolean("showOfflineDevices", true),
                    compactMode = settingsJson.optBoolean("compactMode", false)
                )
            } else AppSettings()

            val nicknamesJson = root.optJSONObject("nicknames")
            val nicknames = mutableMapOf<String, String>()
            if (nicknamesJson != null) {
                nicknamesJson.keys().forEach { mac ->
                    nicknames[mac] = nicknamesJson.optString(mac)
                }
            }

            Pair(settings, nicknames)
        } catch (e: Exception) {
            null // فشل التحليل (ملف تالف أو غير صحيح)
        }
    }

    fun writeToUri(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }
}