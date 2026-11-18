package com.example.routermanager.network

import com.example.routermanager.domain.*
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Xiaomi Mi / Redmi router adapter (LuCI / stok API).
 */
class XiaomiApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand = "Xiaomi"

    override val capabilities = RouterCapabilities(
        changeSsid = true,
        changePassword = true,
        listDevices = true,
        blockDevice = true,
        unblockDevice = true,
        macFilter = false,
        guestNetwork = false,
        reboot = true,
        routerInfo = true,
        wanInfo = true,
    )

    private var stok = ""

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val url = base!!

        val tokenBody = (get("/cgi-bin/luci/api/xqsystem/login") as? RouterResult.Ok)?.value
        val nonce = parseJson(tokenBody ?: "")?.optString("nonce") ?: return@io fail("Xiaomi login failed: no nonce.")
        
        val signed = sha1(nonce + credentials.password)

        val data = mapOf(
            "username" to credentials.username,
            "password" to signed,
            "logtype" to "2",
            "nonce" to nonce
        )
        val loginResult = postForm("/cgi-bin/luci/api/xqsystem/login", data)
        
        val body = when (loginResult) {
            is RouterResult.Ok -> loginResult.value
            is RouterResult.Error -> return@io loginResult
        }

        val json = parseJson(body) ?: return@io fail("Xiaomi unreadable reply.")
        if (json.optInt("code") != 0) return@io fail("Xiaomi login rejected.")
        
        stok = json.optString("token") ?: return@io fail("Xiaomi token missing.")
        ok(RouterSession(stok, cookieJar.snapshot(url.host)))
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun signed(path: String): String {
        return if (path.contains("?")) "$path&stok=$stok" else "$path?stok=$stok"
    }

    // تم إضافة suspend هنا
    private suspend fun apiGet(path: String): RouterResult<JSONObject> {
        val body = when (val r = get(signed(path))) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return fail(r.message)
        }
        val json = parseJson(body) ?: return fail("Xiaomi unreadable reply for $path.")
        return ok(json)
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = when (val r = getConnectedDevices()) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val status = apiGet("/cgi-bin/luci/api/xqnetwork/wan_info")
        
        ok(
            NetworkInfo(
                ssid = readSsid(),
                connected = (status as? RouterResult.Ok)?.value != null,
                clientCount = devices.size,
                clientsOnline = devices.count { it.online },
                clientsTotal = devices.size,
            )
        )
    }

    private suspend fun readSsid(): String? {
        val body = (get(signed("/cgi-bin/luci/api/xqnetwork/wifi_detail_all")) as? RouterResult.Ok)?.value
        return body?.let { find(it, "\"ssid\"\\s*:\\s*\"([^\"]*)\"") }
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        val json = when (val r = apiGet("/cgi-bin/luci/api/xqmisystem/devicelist")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val list = json.optJSONArray("list") ?: return@io ok(emptyList())
        val out = ArrayList<ConnectedDevice>(list.length())
        
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val mac = d.optString("mac")
            out.add(
                ConnectedDevice(
                    name = d.optString("name").takeIf { it.isNotBlank() } ?: "Unknown device",
                    ip = d.optString("ip").takeIf { it.isNotBlank() } ?: "—",
                    mac = formatMac(normalizeMac(mac) ?: mac),
                    online = d.optString("online").toIntOrNull() == 1,
                    blocked = d.optBoolean("is_black"),
                    hostname = d.optString("nick_name").takeIf { it.isNotBlank() },
                    vendor = vendorFor(mac),
                    connectionType = when (d.optString("type")) {
                        "0" -> "Ethernet"
                        "1" -> "Wi-Fi 2.4G"
                        "2" -> "Wi-Fi 5G"
                        else -> null
                    }
                )
            )
        }
        ok(out)
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        val data = mapOf("ssid" to ssid, "band" to if (band == "5G") "1" else "0")
        postForm("/cgi-bin/luci/;stok=$stok/api/xqnetwork/set_wifi_ssid", data).toUnit()
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        val data = mapOf("password" to password, "band" to if (band == "5G") "1" else "0")
        postForm("/cgi-bin/luci/;stok=$stok/api/xqnetwork/set_wifi_password", data).toUnit()
    }

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val cleanMac = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val data = mapOf("mac" to formatMac(cleanMac), "action" to "black")
        postForm("/cgi-bin/luci/;stok=$stok/api/xqsystem/set_mac_filter", data).toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val cleanMac = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val data = mapOf("mac" to formatMac(cleanMac), "action" to "remove")
        postForm("/cgi-bin/luci/;stok=$stok/api/xqsystem/set_mac_filter", data).toUnit()
    }

    override suspend fun reboot(): RouterResult<Unit> = io {
        postForm("/cgi-bin/luci/;stok=$stok/api/xqsystem/reboot", emptyMap()).toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val json = when (val r = apiGet("/cgi-bin/luci/api/xqsystem/init_info")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        ok(
            RouterInfo(
                vendor = "Xiaomi",
                model = json.optString("romversion").takeIf { it.isNotBlank() } ?: "Xiaomi Router",
                firmware = json.optString("romversion"),
                uptimeSeconds = json.optLong("uptime", 0L).takeIf { it > 0L },
                memTotalKb = json.optLong("mem_total", 0L).takeIf { it > 0L },
                memFreeKb = json.optLong("mem_free", 0L).takeIf { it > 0L },
            )
        )
    }

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val json = when (val r = apiGet("/cgi-bin/luci/api/xqnetwork/wan_status")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val info = json.optJSONObject("info") ?: return@io fail("No WAN data.")
        ok(
            WanInfo(
                status = when (info.optString("status")) {
                    "0" -> "Disconnected"
                    "1" -> "Connected"
                    else -> "Unknown"
                },
                ip = info.optString("ip").takeIf { it.isNotBlank() },
                uptimeSeconds = info.optLong("uptime", 0L).takeIf { it > 0L },
                protocol = info.optString("proto").takeIf { it.isNotBlank() },
            )
        )
    }
}