package com.example.routermanager.network

import com.example.routermanager.domain.*
import okhttp3.Request
import org.json.JSONObject

/**
 * Netgear adapter.
 *
 * Modern Netgear routers (R6xxx, R7xxx, Orbi) use the SOAP-ish `/currentsetting.htm` and
 * the `/cgi-bin/` JSON API. Login posts to `/login.cgi` with a hashed password; the reply
 * carries a session token in a cookie. Client list comes from `/DEV_device.htm`, which is
 * a hidden HTML table with one row per attached device.
 */
class NetgearApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand: String = "Netgear"

    override val capabilities = RouterCapabilities(
        changeSsid = true,
        changePassword = true,
        listDevices = true,
        blockDevice = true,
        unblockDevice = true,
        macFilter = true,
        reboot = true,
        routerInfo = true,
        wanInfo = true,
        trafficStats = true,
    )

    private var loggedIn = false

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val url = base!!

        // Netgear reads the login form, then re-posts with the "id" hidden field added.
        val page = (get("/") as? RouterResult.Ok)?.value ?: return@io fail("Cannot reach the Netgear router.")
        val idField = hiddenInput(page, "id") ?: return@io fail("Netgear login page not recognised.")

        val data = linkedMapOf(
            "id" to idField,
            "login_kind" to "password",
            "username" to credentials.username,
            "password" to credentials.password,
            "apply" to "Login",
        )
        val r = postForm("/login.cgi", data)
        val body = (r as? RouterResult.Ok)?.value ?: (r as RouterResult.Error).let { return@io it }

        if (body.contains("password is incorrect", true) || body.contains("rejected", true)) {
            return@io fail("Netgear login failed. Check the password.")
        }
        loggedIn = true
        ok(RouterSession(token = null, cookies = cookieJar.snapshot(url.host)))
    }

    private fun requireSession(): RouterResult<Unit> {
        if (!loggedIn) return fail("Not signed in.")
        return ok(Unit)
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        when (val check = requireSession()) {
            is RouterResult.Error -> return@io check
            is RouterResult.Ok -> Unit
        }
        val body = (get("/currentsetting.htm") as? RouterResult.Ok)?.value
            ?: (get("/DEV_status.htm") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Network status")
        val ssid = find(body, "ssid[^0-9a-zA-Z]*([0-9a-zA-Z_-]{3,32})")
        val clients = Regex("active[^0-9]*([0-9]+)", RegexOption.IGNORE_CASE).find(body)
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
        val body = (get("/DEV_device.htm") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Device list")
        // Each row is <td>MAC</td><td>IP</td><td>name</td>...</tr>
        val rows = Regex("<td>\\s*([0-9a-fA-F:]{17})\\s*</td>\\s*<td>\\s*([0-9.]+)\\s*</td>\\s*<td>\\s*([^<]*)</td>")
            .findAll(body)
        val devices = rows.mapNotNull { m ->
            val mac = normalizeMac(m.groupValues[1]) ?: return@mapNotNull null
            ConnectedDevice(
                name = m.groupValues[3].trim().ifBlank { vendorFor(mac) ?: "Unknown device" },
                ip = m.groupValues[2],
                mac = formatMac(mac),
                online = true,
                blocked = false,
                hostname = m.groupValues[3].trim().ifBlank { null },
                vendor = vendorFor(mac),
                connectionType = "LAN/Wi-Fi",
            )
        }.toList()
        ok(devices)
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        if (ssid.isBlank() || ssid.length > 32) return@io fail("Network name must be 1-32 characters.")
        val r = postForm(
            "/cgi-bin/genie.cgi",
            mapOf("ssid" to ssid, "band" to (band ?: "2.4G"), "apply" to "Apply"),
        )
        r.toUnit()
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        if (password.length < 8 || password.length > 63) {
            return@io fail("Wi-Fi password must be 8-63 characters.")
        }
        val r = postForm(
            "/cgi-bin/genie.cgi",
            mapOf("passphrase" to password, "band" to (band ?: "2.4G"), "apply" to "Apply"),
        )
        r.toUnit()
    }

    // ----------------------------------------------------------- device block

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val r = postForm(
            "/cgi-bin/genie.cgi",
            mapOf("mac" to formatMac(clean), "action" to "block", "apply" to "Apply"),
        )
        r.toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val r = postForm(
            "/cgi-bin/genie.cgi",
            mapOf("mac" to formatMac(clean), "action" to "unblock", "apply" to "Apply"),
        )
        r.toUnit()
    }

    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("\"$mac\" is not a valid MAC address.")
        val r = postForm(
            "/cgi-bin/genie.cgi",
            mapOf("mac" to formatMac(clean), "name" to description, "enable" to if (enabled) "1" else "0"),
        )
        r.toUnit()
    }

    // ---------------------------------------------------------------- system

    override suspend fun reboot(): RouterResult<Unit> = io {
        val r = postForm("/cgi-bin/genie.cgi", mapOf("action" to "reboot"))
        r.toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val body = (get("/currentsetting.htm") as? RouterResult.Ok)?.value
            ?: return@io notSupported("Router information")
        ok(
            RouterInfo(
                vendor = "Netgear",
                model = find(body, "model[^0-9a-zA-Z]*([A-Za-z0-9._-]{2,24})") ?: "Unknown",
                firmware = find(body, "firmware[^0-9a-zA-Z]*([0-9][0-9a-zA-Z._-]{2,24})") ?: "Unknown",
                serial = find(body, "serial[^0-9a-zA-Z]*([A-Z0-9]{4,20})"),
            ),
        )
    }

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val body = (get("/currentsetting.htm") as? RouterResult.Ok)?.value
            ?: return@io notSupported("WAN status")
        val ip = find(body, "([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})")
        ok(
            WanInfo(
                status = if (ip != null) "Connected" else "Unknown",
                ip = ip,
                uptimeSeconds = find(body, "uptime[^0-9]*([0-9]+)")?.toLongOrNull(),
            ),
        )
    }
}