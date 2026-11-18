package com.example.routermanager.network

import com.example.routermanager.domain.*

class HuaweiApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand = "Huawei (WE / TE Data)"
    
    override val capabilities = RouterCapabilities(
        changeSsid = false, changePassword = false, listDevices = true,
        blockDevice = true, unblockDevice = true, macFilter = true,
        guestNetwork = false, reboot = true, routerInfo = true, wanInfo = true
    )

    private var token = ""

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val url = base!!

        val tokenPage = when (val r = get("/asp/GetRandCount.asp")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io fail("Cannot reach Huawei router.")
        }
        
        token = tokenPage.replace(Regex("[^a-zA-Z0-9]"), "")
        if (token.isBlank()) return@io fail("Huawei token extraction failed.")

        val payload = "UserName=${credentials.username}&PassWord=${credentials.password}&Language=english&x.X_HW_Token=$token"
        val loginResult = postBody("/login.cgi", payload, "application/x-www-form-urlencoded")
        
        when (loginResult) {
            is RouterResult.Ok -> {
                val body = loginResult.value
                if (body.contains("error", ignoreCase = true) || body.contains("404")) {
                    return@io fail("Huawei login failed.")
                }
                ok(RouterSession(token, cookieJar.snapshot(url.host)))
            }
            is RouterResult.Error -> return@io loginResult
        }
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        val body = when (val r = get("/html/bbsp/common/GetLanUserDevInfo.asp")) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val list = ArrayList<ConnectedDevice>()
        val regex = Regex("\"([0-9a-fA-F-]{17})\",\"([0-9\\.]+)\",\"([^\"]*)\"")
        regex.findAll(body).forEach { m ->
            val mac = m.groupValues[1].replace("-", ":").uppercase()
            list.add(ConnectedDevice(name = m.groupValues[3].ifBlank { "Unknown" }, ip = m.groupValues[2], mac = formatMac(mac)))
        }
        ok(list)
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = (getConnectedDevices() as? RouterResult.Ok)?.value.orEmpty()
        ok(NetworkInfo(ssid = "WE / TE Data", connected = true, clientCount = devices.size, clientsOnline = devices.size, clientsTotal = devices.size))
    }

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "x.X_HW_Token=$token&MacAddress=${formatMac(clean)}&DeviceName=Blocked&Enable=1"
        postBody("/html/bbsp/macfilter/macfilter.asp", payload, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "x.X_HW_Token=$token&MacAddress=${formatMac(clean)}&DeviceName=Blocked&Enable=0"
        postBody("/html/bbsp/macfilter/macfilter.asp", payload, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun reboot(): RouterResult<Unit> = io {
        postBody("/html/bbsp/common/reboot.asp", "x.X_HW_Token=$token", "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        ok(RouterInfo(vendor = "Huawei", model = "HG8245H", firmware = "Unknown"))
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = notSupported("Change SSID")
    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = notSupported("Change Password")
    override suspend fun restartWifi(): RouterResult<Unit> = notSupported("Restart Wi-Fi")
}