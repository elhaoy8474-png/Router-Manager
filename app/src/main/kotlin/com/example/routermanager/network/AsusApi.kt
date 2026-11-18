package com.example.routermanager.network

import com.example.routermanager.domain.*
import org.json.JSONObject
import java.net.URLEncoder

class AsusApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand: String = "ASUS"

    override val capabilities = RouterCapabilities(
        changeSsid = true, changePassword = true, listDevices = true,
        blockDevice = true, unblockDevice = true, macFilter = true,
        guestNetwork = true, reboot = true, routerInfo = true, wanInfo = true,
    )

    private var authToken: String? = null

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val url = base!!

        val page = when (val p = get("/Main_Login.asp")) {
            is RouterResult.Ok -> p.value
            is RouterResult.Error -> return@io p
        }
        val nonce = hiddenInput(page, "authResponse") ?: return@io fail("ASUS login page not recognised.")

        val data = linkedMapOf(
            "action_mode" to "apply", "current_page" to "Main_Login.asp", "next_page" to "index.asp",
            "group_id" to "ChangeLAN", "action_script" to "appGet.cgi",
            "login_authorization" to credentials.password, "authResponse" to nonce,
        )
        val body = when (val r = postForm("/appGet.cgi", data)) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }

        if (!body.contains("127.0.0.1", true) && !body.contains("error_page", true)) {
            return@io fail("ASUS login failed. Check the password.")
        }
        authToken = nonce
        ok(RouterSession(nonce, cookieJar.snapshot(url.host)))
    }

    private suspend fun hook(name: String): RouterResult<JSONObject> {
        if (authToken == null) return fail("Not signed in.")
        val body = when (val r = get("/appGet.cgi?hook=${name}()")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return fail(r.message)
        }
        val json = parseJson(body) ?: return fail("Unreadable ASUS reply for $name.")
        return ok(json)
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = when (val r = getConnectedDevices()) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val wanOk = hook("get_wan_info") is RouterResult.Ok
        val ssid = readSsid()
        val guest = when (val g = getGuestNetworks()) {
            is RouterResult.Ok -> g.value.firstOrNull { it.enabled }
            is RouterResult.Error -> null
        }
        ok(NetworkInfo(ssid, wanOk, devices.size, devices.count { it.online }, devices.size, guest?.ssid, guest != null))
    }

    private suspend fun readSsid(): String? {
        val body = when (val r = get("/appGet.cgi?hook=get_wl_ssid()")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return null
        }
        return find(body, "\"ssid\"\\s*:\\s*\"([^\"]*)\"")
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        val json = when (val r = hook("get_clientlist")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val list = json.optJSONObject("get_clientlist")?.optJSONArray("list") ?: json.optJSONArray("list") ?: return@io ok(emptyList<ConnectedDevice>())
        val blockedMacs = readMacFilters().filter { it.enabled }.map { it.mac }.toSet()
        val out = ArrayList<ConnectedDevice>(list.length())
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val rawMac = d.optString("mac")
            val formatted = normalizeMac(rawMac)?.let { formatMac(it) } ?: rawMac
            val online = d.optString("cur").toIntOrNull()?.let { it > 0 } ?: true
            out.add(ConnectedDevice(
                name = d.optString("name").takeIf { it.isNotBlank() } ?: "Unknown",
                ip = d.optString("ip").takeIf { it.isNotBlank() } ?: "—",
                mac = formatted, online = online, blocked = formatted in blockedMacs,
                hostname = d.optString("nickName").takeIf { it.isNotBlank() }, vendor = null,
                rssi = d.optString("rssi").toIntOrNull(),
                connectionType = when (d.optString("type")) { "0" -> "Ethernet"; "1" -> "Wi-Fi 2.4G"; "2" -> "Wi-Fi 5G"; else -> null }
            ))
        }
        ok(out)
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        if (ssid.isBlank() || ssid.length > 32) return@io fail("Network name must be 1-32 characters.")
        postForm("/appGet.cgi?hook=set_wl_ssid()", mapOf("ssid" to ssid, "wl_unit" to if (band == "5G") "1" else "0")).toUnit()
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        if (password.length < 8 || password.length > 63) return@io fail("Wi-Fi password must be 8-63 characters.")
        postForm("/appGet.cgi?hook=set_wl_key()", mapOf("key" to password, "wl_unit" to if (band == "5G") "1" else "0")).toUnit()
    }

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        when (val r = applyMacList(formatMac(clean), blocked = true)) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        when (val r = applyMacList(formatMac(clean), blocked = false)) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    private suspend fun applyMacList(mac: String, blocked: Boolean): RouterResult<String> {
        val xml = "<maclist><mac>$mac</mac><name>Router Manager</name><blocked>${if (blocked) "1" else "0"}</blocked></maclist>"
        val body = "action_mode=apply&maclist=" + URLEncoder.encode(xml, "UTF-8")
        return postBody("/appGet.cgi?hook=set_maclist()", body, "application/x-www-form-urlencoded")
    }

    private suspend fun readMacFilters(): List<MacFilterRule> {
        val body = when (val r = get("/appGet.cgi?hook=get_maclist()")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return emptyList()
        }
        val re = Regex("<mac>([0-9a-fA-F:]{17})</mac>\\s*<name>([^<]*)</name>(?:\\s*<blocked>([01])</blocked>)?")
        return re.findAll(body).map { m ->
            MacFilterRule(m.groupValues[1].uppercase(), m.groupValues[2], m.groupValues.getOrNull(3)?.let { it == "1" } ?: true)
        }.toList()
    }

    override suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> = io { ok(readMacFilters()) }

    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val xml = "<maclist><mac>${formatMac(clean)}</mac><name>$description</name><blocked>${if (enabled) "1" else "0"}</blocked></maclist>"
        val body = "action_mode=apply&maclist=" + URLEncoder.encode(xml, "UTF-8")
        postBody("/appGet.cgi?hook=set_maclist()", body, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun removeMacFilter(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val xml = "<maclist><mac>${formatMac(clean)}</mac><name></name><blocked>0</blocked></maclist>"
        val body = "action_mode=apply&maclist=" + URLEncoder.encode(xml, "UTF-8")
        postBody("/appGet.cgi?hook=set_maclist()", body, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun getGuestNetworks(): RouterResult<List<GuestNetwork>> = io {
        val body = when (val r = get("/appGet.cgi?hook=get_wlguest_ssid()")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io ok(emptyList<GuestNetwork>())
        }
        val out = ArrayList<GuestNetwork>(2)
        listOf("2.4G" to "get_wlguest_ssid", "5G" to "get_wlguest_ssid_5g").forEach { (band, _) ->
            val b = Regex.escape(band)
            val ssid = find(body, "\"ssid_$b\"\\s*:\\s*\"([^\"]*)\"") ?: find(body, "\"${b}ssid\"\\s*:\\s*\"([^\"]*)\"")
            if (ssid != null) {
                val enabled = find(body, "\"enable_$b\"\\s*:\\s*\"?([01])\"?") == "1"
                out.add(GuestNetwork(band, ssid, enabled))
            }
        }
        ok(out)
    }

    override suspend fun setGuestNetwork(band: String, enabled: Boolean, ssid: String): RouterResult<Unit> = io {
        postForm("/appGet.cgi?hook=set_wlguest_ssid()", mapOf("ssid" to ssid, "enable" to if (enabled) "1" else "0", "band" to band)).toUnit()
    }

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val json = when (val r = hook("get_wan_info")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val info = json.optJSONObject("get_wan_info") ?: json
        val ip = info.optString("ip").takeIf { it.isNotBlank() } ?: info.optString("wan_ipaddr").takeIf { it.isNotBlank() }
        val status = when (info.optString("status")) { "0" -> "Disconnected"; "2" -> "Connected"; else -> info.optString("status").takeIf { it.isNotBlank() } ?: "Unknown" }
        val uptime = info.optLong("uptime", 0L).takeIf { it > 0L } ?: info.optString("connUptime").toLongOrNull()
        val dnsArr = info.optJSONArray("dns")
        val dns = if (dnsArr != null) (0 until dnsArr.length()).map { dnsArr.optString(it) }.filter { it.isNotBlank() } else emptyList()
        ok(WanInfo(status, ip, uptime, info.optString("proto").takeIf { it.isNotBlank() }, dns))
    }

    override suspend fun reboot(): RouterResult<Unit> = io {
        postForm("/appGet.cgi?hook=reboot()", mapOf("action_mode" to "apply")).toUnit()
    }

    override suspend fun restartWifi(): RouterResult<Unit> = io {
        postForm("/appGet.cgi?hook=restart_wireless()", mapOf("action_mode" to "apply")).toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val json = when (val r = hook("get_status")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val info = json.optJSONObject("get_status") ?: json
        val firmware = info.optString("firmver").takeIf { it.isNotBlank() } ?: info.optString("buildno").takeIf { it.isNotBlank() } ?: "Unknown"
        val cpu = info.optDouble("cpu_usage", -1.0).takeIf { it >= 0.0 }?.toFloat()
        ok(RouterInfo(
            vendor = "ASUS",
            model = info.optString("model").takeIf { it.isNotBlank() } ?: "ASUS router",
            firmware = firmware,
            uptimeSeconds = info.optLong("uptime", 0L).takeIf { it > 0L },
            cpuLoad = cpu,
            memTotalKb = info.optLong("mem_total", 0L).takeIf { it > 0L },
            memFreeKb = info.optLong("mem_free", 0L).takeIf { it > 0L }
        ))
    }

    override suspend fun changeDns(primary: String, secondary: String): RouterResult<Unit> = io {
        val payload = mapOf("action_mode" to "apply", "dns1" to primary, "dns2" to secondary)
        postForm("/appGet.cgi?hook=set_wan_dns()", payload).toUnit()
    }
}