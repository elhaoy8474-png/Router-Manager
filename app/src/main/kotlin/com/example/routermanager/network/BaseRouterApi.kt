package com.example.routermanager.network

import com.example.routermanager.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

abstract class BaseRouterApi(
    protected val client: OkHttpClient = RouterHttpClient.client,
    protected val cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar
) : RouterApi {

    protected var base: HttpUrl? = null

    protected suspend fun <T> io(block: suspend () -> RouterResult<T>): RouterResult<T> = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: Exception) {
            RouterResult.Error(e.message ?: "Network error", e)
        }
    }

    protected fun <T> ok(value: T): RouterResult<T> = RouterResult.Ok(value)
    protected fun <T> fail(message: String): RouterResult<T> = RouterResult.Error(message)
    protected fun notSupported(feature: String): RouterResult<Nothing> = RouterResult.Error("$feature is not supported for $brand.")

    protected suspend fun get(path: String, headers: Map<String, String> = emptyMap()): RouterResult<String> {
        val url = base?.resolve(path) ?: return fail("Session not initialised.")
        return try {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail("$path failed (HTTP ${response.code}).")
                ok(text)
            }
        } catch (e: Exception) {
            fail("$path failed: ${e.message}")
        }
    }

    protected suspend fun postForm(path: String, formData: Map<String, String>, headers: Map<String, String> = emptyMap()): RouterResult<String> {
        val url = base?.resolve(path) ?: return fail("Session not initialised.")
        return try {
            val form = FormBody.Builder().apply { formData.forEach { (k, v) -> add(k, v) } }.build()
            val builder = Request.Builder().url(url).post(form)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail("$path failed (HTTP ${response.code}).")
                ok(text)
            }
        } catch (e: Exception) {
            fail("$path failed: ${e.message}")
        }
    }

    protected suspend fun postBody(path: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): RouterResult<String> {
        val url = base?.resolve(path) ?: return fail("Session not initialised.")
        return try {
            val media = contentType.toMediaType()
            val builder = Request.Builder().url(url).post(body.toRequestBody(media))
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail("$path failed (HTTP ${response.code}).")
                ok(text)
            }
        } catch (e: Exception) {
            fail("$path failed: ${e.message}")
        }
    }

    protected fun parseJson(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
    
    protected fun hiddenInput(html: String, name: String): String? {
        val regex = Regex("<input[^>]*name=[\"']$name[\"'][^>]*value=[\"']([^\"']*)[\"']")
        return regex.find(html)?.groupValues?.get(1)
    }
    
    protected fun find(text: String, pattern: String, group: Int = 1): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(group)

    protected fun normalizeMac(mac: String): String? {
        val hex = mac.trim().removePrefix("0x").replace(Regex("[^0-9a-fA-F]"), "")
        return if (hex.length == 12) hex.uppercase() else null
    }

    protected fun formatMac(hex: String): String = hex.chunked(2).joinToString(":")

    protected fun RouterResult<*>.toUnit(): RouterResult<Unit> = when (this) {
        is RouterResult.Ok -> ok(Unit)
        is RouterResult.Error -> this
    }

    // إعادة دالة vendorFor التي كانت مفقودة
    protected fun vendorFor(mac: String): String? {
        val hex = normalizeMac(mac) ?: return null
        return MacVendors[hex.take(6)]
    }

    override suspend fun changeDns(primary: String, secondary: String): RouterResult<Unit> = io { notSupported("Change DNS") }
    override suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> = io { ok(emptyList()) }
    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io { notSupported("MAC Filter") }
    override suspend fun removeMacFilter(mac: String): RouterResult<Unit> = io { notSupported("Remove MAC Filter") }
    override suspend fun getGuestNetworks(): RouterResult<List<GuestNetwork>> = io { ok(emptyList()) }
    override suspend fun setGuestNetwork(band: String, enabled: Boolean, ssid: String): RouterResult<Unit> = io { notSupported("Guest Network") }
    override suspend fun restartWifi(): RouterResult<Unit> = io { notSupported("Restart Wi-Fi") }
}

// إعادة كائن MacVendors
object MacVendors {
    val map: Map<String, String> = mapOf(
        "50C7BF" to "TP-Link", "A42BB0" to "TP-Link", "B0BE76" to "TP-Link", "EC086B" to "TP-Link",
        "00E0FC" to "Huawei", "48D539" to "Huawei", "E0247C" to "Huawei",
        "00158A" to "ZTE", "34DBFD" to "ZTE", "CC7B35" to "ZTE", "E0B021" to "ZTE",
        "286C07" to "Xiaomi", "64648B" to "Xiaomi", "F8A45F" to "Xiaomi",
        "2C56DC" to "ASUS", "AC220B" to "ASUS", "D850E6" to "ASUS",
        "204E7F" to "Netgear", "A040A0" to "Netgear", "C03F0E" to "Netgear",
        "1CA63C" to "D-Link", "5CD998" to "D-Link", "B4B382" to "D-Link",
        "C8D3A3" to "Tenda", "D8A25E" to "Tenda", "002275" to "Tenda", "547398" to "Totolink", "DCE2C6" to "Totolink",
        "F0D79E" to "Apple", "3C0754" to "Apple", "001C25" to "Samsung", "5001BB" to "Samsung",
        "001DD8" to "OPPO", "A02510" to "Nokia", "00E04C" to "Realtek",
    )

    operator fun get(oui: String): String? = map[oui.uppercase()]
}