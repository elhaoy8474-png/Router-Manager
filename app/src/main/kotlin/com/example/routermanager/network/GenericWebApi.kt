package com.example.routermanager.network

import com.example.routermanager.domain.*

/**
 * Configuration-driven adapter for the Huawei / ZTE / Totolink / Tenda web UIs.
 *
 * These firmwares are all "form-post a page and scrape the HTML/JSON table back" UIs, so a
 * single adapter can serve them by swapping the paths and field names in a [Profile].
 * Everything unsupported by a given profile reports a clear error instead of guessing.
 */
class GenericWebApi(
    override val brand: String,
    private val profile: Profile,
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val capabilities = profile.capabilities

    private var loggedIn = false

    /** Paths and field names that differ between firmwares. */
    data class Profile(
        val capabilities: RouterCapabilities,
        val loginPath: String,
        val loginFields: List<Pair<String, String>>,
        val loginSuccessMarker: String,
        val devicesPath: String,
        val devicesTableRegex: String,
        val ssidPath: String? = null,
        val ssidField: String? = null,
        val passwordPath: String? = null,
        val passwordField: String? = null,
        val blockPath: String? = null,
        val blockField: String? = null,
        val rebootPath: String? = null,
        val statusPath: String? = null,
        val macFilterPath: String? = null,
        val guestPath: String? = null,
    )

    // ---------------------------------------------------------------- login

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val data = profile.loginFields.toMap().toMutableMap()
        data["username"] = credentials.username
        data["password"] = credentials.password

        val result = postForm(profile.loginPath, data)
        if (result is RouterResult.Error) return@io result

        val body = (result as RouterResult.Ok).value
        if (!body.contains(profile.loginSuccessMarker, ignoreCase = true)) {
            return@io fail("$brand login failed. Check the address, username and password.")
        }
        loggedIn = true
        ok(RouterSession(token = null, cookies = cookieJar.snapshot(base!!.host)))
    }

    private fun requireSession(): RouterResult<Unit> {
        if (!loggedIn) return fail("Not signed in.")
        return ok(Unit)
    }

    // ---------------------------------------------------------------- status

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        when (val check = requireSession()) {
            is RouterResult.Error -> return@io check
            is RouterResult.Ok -> Unit
        }
        val body = (get("/cgi-bin/baseinfoSet.cgi") as? RouterResult.Ok)?.value
            ?: (get(profile.statusPath ?: "/status.html") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Network status")

        val ssid = find(body, "ssid[^0-9a-zA-Z]*([0-9a-zA-Z_-]{3,32})")
        val clients = Regex("var\\s+wlan\\s*active\\s*=\\s*'?([^';]+)").find(body)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        ok(
            NetworkInfo(
                ssid = ssid,
                connected = true,
                clientCount = clients,
                clientsOnline = clients,
                clientsTotal = clients,
            ),
        )
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        when (val check = requireSession()) {
            is RouterResult.Error -> return@io check
            is RouterResult.Ok -> Unit
        }
        val body = (get(profile.devicesPath) as? RouterResult.Ok)?.value
            ?: return@io notSupported("Device list")

        // Most of these UIs render a plain HTML table with one row per client.
        val rows = Regex(profile.devicesTableRegex, RegexOption.IGNORE_CASE).findAll(body)
        val devices = rows.mapNotNull { m ->
            val mac = m.groupValues.getOrNull(1)?.let { normalizeMac(it) } ?: return@mapNotNull null
            val ip = m.groupValues.getOrNull(2).orEmpty()
            val name = m.groupValues.getOrNull(3)?.trim().orEmpty()
            ConnectedDevice(
                name = name.ifBlank { vendorFor(mac) ?: "Unknown device" },
                ip = ip,
                mac = formatMac(mac),
                online = true,
                blocked = false,
                hostname = name.ifBlank { null },
                vendor = vendorFor(mac),
                connectionType = if (ip.startsWith("192.168")) "LAN/Wi-Fi" else null,
            )
        }.toList()
        ok(devices)
    }

    // ------------------------------------------------------------ wi-fi edit

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        val path = profile.ssidPath ?: return@io notSupported("Wi-Fi name change")
        val field = profile.ssidField ?: "ssid"
        if (ssid.isBlank() || ssid.length > 32) return@io fail("Network name must be 1-32 characters.")
        val r = postForm(path, mapOf(field to ssid, "submit" to "Apply"))
        r.toUnit()
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        val path = profile.passwordPath ?: return@io notSupported("Wi-Fi password change")
        val field = profile.passwordField ?: "WscKeyEntry"
        if (password.length < 8 || password.length > 63) {
            return@io fail("Wi-Fi password must be 8-63 characters.")
        }
        val r = postForm(path, mapOf(field to password, "submit" to "Apply"))
        r.toUnit()
    }

    // ----------------------------------------------------------- device block

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val path = profile.blockPath ?: return@io notSupported("Blocking devices")
        val field = profile.blockField ?: "MAC"
        val r = postForm(path, mapOf(field to formatMac(clean), "action" to "block"))
        r.toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val path = profile.blockPath ?: return@io notSupported("Unblocking devices")
        val field = profile.blockField ?: "MAC"
        val r = postForm(path, mapOf(field to formatMac(clean), "action" to "unblock"))
        r.toUnit()
    }

    // ------------------------------------------------------------- MAC filter

    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io {
        val path = profile.macFilterPath ?: return@io notSupported("MAC filtering")
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val r = postForm(
            path,
            mapOf("MAC" to formatMac(clean), "desc" to description, "enable" to if (enabled) "1" else "0"),
        )
        r.toUnit()
    }

    // ---------------------------------------------------------------- system

    override suspend fun reboot(): RouterResult<Unit> = io {
        val path = profile.rebootPath ?: return@io notSupported("Reboot")
        val r = postForm(path, mapOf("action" to "reboot"))
        r.toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val body = (get(profile.statusPath ?: "/") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Router information")
        ok(
            RouterInfo(
                vendor = brand,
                model = find(body, "model[^0-9a-zA-Z]*([A-Za-z0-9._-]{2,24})") ?: "Unknown",
                firmware = find(body, "(?:firmware|software)[^0-9a-zA-Z]*([0-9][0-9a-zA-Z._-]{2,24})")
                    ?: "Unknown",
            ),
        )
    }

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val path = profile.statusPath ?: return@io notSupported("WAN status")
        val body = (get(path) as? RouterResult.Ok)?.value ?: return@io notSupported("WAN status")
        val ip = find(body, "([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})")
        val up = find(body, "uptime[^0-9]*([0-9]+)")
        ok(
            WanInfo(
                status = if (ip != null) "Connected" else "Unknown",
                ip = ip,
                uptimeSeconds = up?.toLongOrNull(),
                signalDbm = find(body, "signal[^0-9-]*(-?[0-9]{1,3})")?.toIntOrNull(),
            ),
        )
    }
}