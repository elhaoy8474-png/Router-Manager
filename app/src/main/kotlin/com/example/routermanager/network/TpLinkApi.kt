package com.example.routermanager.network

import com.example.routermanager.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * TP-Link adapter for the "stok" web firmware used by Archer/CPE/Deco-class devices
 * (the `/cgi-bin/luci/;stok=<token>/...` scheme).
 *
 * Two blocking back-ends are attempted, because TP-Link moved the endpoint between
 * firmware generations: first the modern `/admin/macfilter` form API, then the older
 * `?form=block_device` device page. Whichever the firmware understands is remembered
 * so later calls go straight there.
 */
class TpLinkApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand: String = "TP-Link"

    override val capabilities = RouterCapabilities(
        changeSsid = true,
        changePassword = true,
        listDevices = true,
        blockDevice = true,
        unblockDevice = true,
        macFilter = true,
        guestNetwork = true,
        reboot = true,
        routerInfo = true,
        wanInfo = true,
        parentalControl = true,
        trafficStats = true,
    )

    private var stok: String? = null
    private var blockMode: BlockMode? = null

    /** Which device-control endpoint this firmware answers on. */
    private enum class BlockMode { MacFilterForm, DevicePage }

    private val token: String?
        get() = stok

    // ---------------------------------------------------------------- login

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> =
        io {
            base = RouterHttpClient.baseUrl(credentials.host)
            val url = base!!
            val body = FormBody.Builder()
                .add("operation", "login")
                .add("username", credentials.username)
                .add("password", credentials.password)
                .build()
            val request = Request.Builder().url(url.resolve("/cgi-bin/luci/")!!).post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val token = find(text, "\"stok\"\\s*:\\s*\"([^\"]+)\"")
                    ?: find(text, "\"stok\"\\s*:\\s*'([^']+)'")
                if (token == null) {
                    val reason = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        text.contains("fail", true) -> "wrong username or password"
                        else -> "unsupported login page"
                    }
                    return@io RouterResult.Error("TP-Link login failed ($reason).")
                }
                stok = token
                RouterResult.Ok(RouterSession(token, cookieJar.snapshot(url.host)))
            }
        }

    /** Builds a full URL under the authenticated stok scope. */
    private fun stokUrl(path: String): okhttp3.HttpUrl? {
        val base = base ?: return null
        val tok = token ?: return null
        return base.resolve("/cgi-bin/luci/;stok=$tok$path")
    }

    /** GET inside the stok scope. */
    private fun stokGet(path: String): RouterResult<String> {
        val url = stokUrl(path) ?: return fail("Session expired. Sign in again.")
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail("HTTP ${response.code}")
                ok(text)
            }
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /** POST form data inside the stok scope. */
    private fun stokPost(path: String, data: Map<String, String>): RouterResult<String> {
        val url = stokUrl(path) ?: return fail("Session expired. Sign in again.")
        return try {
            val form = FormBody.Builder().apply { data.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url(url).post(form).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail("HTTP ${response.code}")
                // TP-Link reports failures inside a 200 body as {"success":false,...}
                parseJson(text)?.let { json ->
                    if (json.optBoolean("success", true).not() ||
                        json.optString("errorcode", "0") != "0"
                    ) {
                        return fail(json.optString("errmsg", "Router rejected the change."))
                    }
                }
                ok(text)
            }
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- status

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = when (val r = getConnectedDevices()) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val wifi = stokGet("/admin/wireless?form=wireless_2g")
        val ssid = (wifi as? RouterResult.Ok)?.value?.let { find(it, "\"ssid\"\\s*:\\s*\"([^\"]*)\"") }
        val enabled = (wifi as? RouterResult.Ok)?.value?.let { find(it, "\"enable\"\\s*:\\s*\"?([01])\"?") }
        val guest = (getGuestNetworks() as? RouterResult.Ok)?.value
            ?.firstOrNull { it.band == "2.4G" }
        ok(
            NetworkInfo(
                ssid = ssid,
                connected = true,
                clientCount = devices.size,
                clientsOnline = devices.count { it.online },
                clientsTotal = devices.size,
                guestSsid = guest?.ssid,
                guestEnabled = guest?.enabled == true,
            ),
        )
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        // Prefer the device page; it lists offline clients too. Fall back to DHCP leases.
        val fromPage = readDevicePage()
        if (fromPage != null) return@io ok(fromPage)

        val leases = readDhcpLeases()
        if (leases != null) return@io ok(leases)

        val filters = readMacFilters()
        ok(filters.map { rule ->
            ConnectedDevice(
                name = rule.description.ifBlank { "Filtered device" },
                ip = "—",
                mac = rule.mac,
                online = false,
                blocked = true,
                hostname = rule.description,
                vendor = vendorFor(rule.mac),
            )
        })
    }

    /** Parses /admin/device or /admin/client list from whichever endpoint answers. */
    private fun readMacFilters(): List<MacFilterRule> {
        val body = (stokGet("/admin/macfilter?form=whitelist") as? RouterResult.Ok)?.value ?: return emptyList()
        return Regex("\"mac\"\\s*:\\s*\"([0-9a-fA-F:]{17})\"").findAll(body)
            .mapNotNull { m ->
                val mac = m.groupValues[1].uppercase()
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                MacFilterRule(mac = mac, description = name.orEmpty(), enabled = true)
            }
            .toList()
    }

    private fun readDevicePage(): List<ConnectedDevice>? {
        val body = (stokGet("/admin/device?form=client") as? RouterResult.Ok)?.value
            ?: (stokGet("/admin/client?form=client") as? RouterResult.Ok)?.value
            ?: return null
        val json = parseJson(body) ?: return null
        val array = json.optJSONArray("device_list") ?: return null
        val blocked = readMacFilters().map { it.mac }.toSet()
        val out = ArrayList<ConnectedDevice>(array.length())
        for (i in 0 until array.length()) {
            val d = array.optJSONObject(i) ?: continue
            val mac = d.optString("mac").uppercase()
            out.add(
                ConnectedDevice(
                    name = d.optString("hostname").ifBlank { "Unknown device" },
                    ip = d.optString("ip"),
                    mac = mac,
                    online = d.optBoolean("online", true),
                    blocked = mac in blocked,
                    hostname = d.optString("hostname"),
                    vendor = vendorFor(mac),
                    connectionType = when (d.optString("type")) {
                        "0" -> "Wi-Fi"
                        "1" -> "Ethernet"
                        else -> d.optString("type").ifBlank { null }
                    },
                ),
            )
        }
        return out
    }

    private fun readDhcpLeases(): List<ConnectedDevice>? {
        val body = (stokGet("/admin/dhcpc?form=status") as? RouterResult.Ok)?.value
            ?: (stokGet("/admin/dhcp?form=lease") as? RouterResult.Ok)?.value
            ?: return null
        val json = parseJson(body) ?: return null
        val array = json.optJSONArray("lease_list") ?: return null
        val out = ArrayList<ConnectedDevice>(array.length())
        for (i in 0 until array.length()) {
            val d = array.optJSONObject(i) ?: continue
            val mac = d.optString("macaddr").uppercase()
            out.add(
                ConnectedDevice(
                    name = d.optString("hostname").ifBlank { "Unknown device" },
                    ip = d.optString("ipaddr"),
                    mac = mac,
                    online = true,
                    blocked = false,
                    hostname = d.optString("hostname"),
                    vendor = vendorFor(mac),
                    connectionType = "Ethernet",
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------ wi-fi edit

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        if (ssid.isBlank() || ssid.length > 32) return@io fail("Network name must be 1-32 characters.")
        val target = band ?: "wireless_2g"
        val form = when (target) {
            "5G", "wireless_5g" -> "wireless_5g"
            else -> "wireless_2g"
        }
        when (val r = stokPost("/admin/wireless?form=$form", mapOf("ssid" to ssid))) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        if (password.length < 8 || password.length > 63) {
            return@io fail("Wi-Fi password must be 8-63 characters.")
        }
        val form = when (band) {
            "5G", "wireless_5g" -> "wireless_5g"
            else -> "wireless_2g"
        }
        when (val r = stokPost(
            "/admin/wireless?form=$form",
            mapOf("key" to password, "passwd" to password, "hide_ssid" to "0", "security" to "WPA2-PSK"),
        )) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    // ----------------------------------------------------------- device block

    /**
     * Blocks a device. Tries the macfilter form first, then the older device page.
     * The successful mode is cached so repeated calls do not probe both.
     */
    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val formatted = formatMac(clean)
        val mode = blockMode ?: probeBlockMode()
        val result = when (mode) {
            null -> return@io fail("This firmware has no device-blocking endpoint.")
            BlockMode.MacFilterForm -> applyMacFilter(formatted, "Blocked from Router Manager", true)
            BlockMode.DevicePage -> stokPost(
                "/admin/device?form=block",
                mapOf("mac" to formatted, "block" to "1"),
            )
        }
        when (result) {
            is RouterResult.Ok -> {
                blockMode = mode
                ok(Unit)
            }
            is RouterResult.Error -> result
        }
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val formatted = formatMac(clean)
        val mode = blockMode ?: probeBlockMode()
        val result = when (mode) {
            null -> return@io fail("This firmware has no device-blocking endpoint.")
            BlockMode.MacFilterForm -> removeMacFilter(formatted)
            BlockMode.DevicePage -> stokPost(
                "/admin/device?form=block",
                mapOf("mac" to formatted, "block" to "0"),
            )
        }
        when (result) {
            is RouterResult.Ok -> {
                blockMode = mode
                ok(Unit)
            }
            is RouterResult.Error -> result
        }
    }

    /** Figures out which blocking endpoint this firmware exposes. */
    private fun probeBlockMode(): BlockMode? {
        val filter = stokGet("/admin/macfilter?form=whitelist")
        if (filter is RouterResult.Ok && (filter.value.contains("mac") || filter.value.contains("list"))) {
            return BlockMode.MacFilterForm
        }
        val device = stokGet("/admin/device?form=client")
        if (device is RouterResult.Ok && device.value.contains("mac")) {
            return BlockMode.DevicePage
        }
        return null
    }

    private fun applyMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<String> {
        val data = buildMap {
            put("mac", mac)
            put("desc", description)
            put("enable", if (enabled) "1" else "0")
            put("operation", "add")
        }
        return stokPost("/admin/macfilter?form=blacklist", data)
    }

    // ------------------------------------------------------------- MAC filter

    override suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> = io {
        val body = (stokGet("/admin/macfilter?form=whitelist") as? RouterResult.Ok)?.value
            ?: return@io ok(readMacFilters())
        val rules = Regex("\"mac\"\\s*:\\s*\"([0-9a-fA-F:]{17})\"[^}]*?\"desc\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(body)
            .map { m ->
                MacFilterRule(
                    mac = m.groupValues[1].uppercase(),
                    description = m.groupValues[2],
                    enabled = m.groupValues[2].isNotBlank(),
                )
            }
            .toList()
        ok(rules)
    }

    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        when (val r = applyMacFilter(formatMac(clean), description, enabled)) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    override suspend fun removeMacFilter(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        when (val r = stokPost(
            "/admin/macfilter?form=blacklist",
            mapOf("operation" to "delete", "mac" to formatMac(clean)),
        )) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    // ------------------------------------------------------------ guest wi-fi

    override suspend fun getGuestNetworks(): RouterResult<List<GuestNetwork>> = io {
        val body = (stokGet("/admin/guestnetwork?form=guest_2g") as? RouterResult.Ok)?.value
            ?: return@io ok(emptyList())
        val ssid = find(body, "\"ssid\"\\s*:\\s*\"([^\"]*)\"") ?: ""
        val enabled = find(body, "\"enable\"\\s*:\\s*\"?([01])\"?") == "1"
        val five = (stokGet("/admin/guestnetwork?form=guest_5g") as? RouterResult.Ok)?.value ?: ""
        val ssid5 = find(five, "\"ssid\"\\s*:\\s*\"([^\"]*)\"")
        val out = mutableListOf(GuestNetwork("2.4G", ssid, enabled))
        if (ssid5 != null) {
            out.add(GuestNetwork("5G", ssid5, find(five, "\"enable\"\\s*:\\s*\"?([01])\"?") == "1"))
        }
        ok(out)
    }

    override suspend fun setGuestNetwork(band: String, enabled: Boolean, ssid: String): RouterResult<Unit> = io {
        val form = if (band == "5G") "guest_5g" else "guest_2g"
        when (val r = stokPost(
            "/admin/guestnetwork?form=$form",
            mapOf("enable" to if (enabled) "1" else "0", "ssid" to ssid),
        )) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    // ------------------------------------------------------------------- WAN

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val body = (stokGet("/admin/status?form=wan") as? RouterResult.Ok)?.value
            ?: (stokGet("/admin/network?form=status") as? RouterResult.Ok)?.value
            ?: return@io notSupported("WAN status")
        val json = parseJson(body)
        val status = json?.optString("connect_status") ?: find(body, "\"connect_status\"\\s*:\\s*\"([^\"]*)\"")
        val ip = json?.optString("ipaddr") ?: find(body, "\"ipaddr\"\\s*:\\s*\"([^\"]*)\"")
        ok(
            WanInfo(
                status = when (status) {
                    "0" -> "Disconnected"
                    "1" -> "Connecting"
                    "2" -> "Connected"
                    null -> "Unknown"
                    else -> status
                },
                ip = ip,
                uptimeSeconds = json?.optLong("up_time", 0L)?.takeIf { it > 0 }
                    ?: find(body, "\"up_time\"\\s*:\\s*\"?(\\d+)")?.toLongOrNull(),
                protocol = json?.optString("proto") ?: find(body, "\"proto\"\\s*:\\s*\"([^\"]*)\""),
            ),
        )
    }

    // --------------------------------------------------------------- system

    override suspend fun reboot(): RouterResult<Unit> = io {
        when (val r = stokPost("/admin/system?form=reboot", mapOf("operation" to "reboot"))) {
            is RouterResult.Ok -> ok(Unit)
            is RouterResult.Error -> r
        }
    }

    override suspend fun restartWifi(): RouterResult<Unit> = io {
        val r = stokPost("/admin/wireless?form=restart", mapOf("operation" to "restart"))
        if (r is RouterResult.Ok) return@io ok(Unit)
        return@io stokPost("/admin/system?form=restart_wifi", mapOf("operation" to "restart")).toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val body = (stokGet("/admin/status?form=all") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Router information")
        val json = parseJson(body)
        ok(
            RouterInfo(
                vendor = "TP-Link",
                model = json?.optString("model") ?: find(body, "\"model\"\\s*:\\s*\"([^\"]*)\"") ?: "Unknown",
                firmware = json?.optString("firmware_version")
                    ?: find(body, "\"firmware_version\"\\s*:\\s*\"([^\"]*)\"")
                    ?: "Unknown",
                serial = json?.optString("serial") ?: find(body, "\"serial\"\\s*:\\s*\"([^\"]*)\""),
                hardwareVersion = json?.optString("hard_ver")
                    ?: find(body, "\"hard_ver\"\\s*:\\s*\"([^\"]*)\""),
                uptimeSeconds = json?.optLong("up_time", 0L)?.takeIf { it > 0 }
                    ?: find(body, "\"up_time\"\\s*:\\s*\"?(\\d+)")?.toLongOrNull(),
                cpuLoad = json?.optDouble("cpu_usage", -1.0)?.takeIf { it >= 0 }?.toFloat(),
                memTotalKb = json?.optLong("mem_total", 0L)?.takeIf { it > 0 },
                memFreeKb = json?.optLong("mem_free", 0L)?.takeIf { it > 0 },
                wanMac = json?.optString("wan_mac") ?: find(body, "\"wan_mac\"\\s*:\\s*\"([^\"]*)\""),
            ),
        )
    }

    /** Leaves the session usable for the next call. */
    fun hasSession(): Boolean = token != null
}