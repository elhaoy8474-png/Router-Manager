package com.example.routermanager.network

import com.example.routermanager.domain.*

/**
 * D-Link routers using the HNAP1 XML protocol.
 */
class DLinkApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand = "D-Link"

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
    )

    private var privateLogin = ""

    // تم إضافة suspend هنا
    private suspend fun hnap(action: String, body: String = "<Empty/>"): RouterResult<String> {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <$action xmlns="http://purenetworks.com/HNAP1/">
                  $body
                </$action>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val headers = mapOf(
            "SOAPAction" to "\"http://purenetworks.com/HNAP1/$action\"",
            "Content-Type" to "text/xml; charset=utf-8",
            "HNAP_AUTH" to "$privateLogin 1000"
        )

        val result = postBody("/HNAP1/", payload, contentType = "text/xml; charset=utf-8", headers = headers)
        if (result is RouterResult.Error) return result
        return ok((result as RouterResult.Ok).value)
    }

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        base = RouterHttpClient.baseUrl(credentials.host)
        val url = base!!

        val challenge = hnap("GetChallenge")
        if (challenge is RouterResult.Error) return@io challenge

        privateLogin = "admin" // قمية افتراضية
        val loginResult = hnap("Login", "<Action>request</Action><Username>${credentials.username}</Username>" +
                "<LoginPassword>${credentials.password}</LoginPassword><PrivateLogin>$privateLogin</PrivateLogin>")
        
        if (loginResult is RouterResult.Error) return@io loginResult
        
        ok(RouterSession(null, cookieJar.snapshot(url.host)))
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = when (val r = getConnectedDevices()) {
            is RouterResult.Ok -> r.value
            is RouterResult.Error -> return@io r
        }
        val ssid = readSsid()
        ok(NetworkInfo(ssid, true, devices.size, devices.count { it.online }, devices.size))
    }

    private suspend fun readSsid(): String? {
        val text = (hnap("GetWLanRadioSettings") as? RouterResult.Ok)?.value ?: return null
        return find(text, "<SSID>([^<]*)</SSID>")
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        val text = (hnap("GetClientInfo") as? RouterResult.Ok)?.value ?: return@io fail("Failed to get clients.")
        val blocked = readMacFilters().map { it.mac }.toSet()
        val out = ArrayList<ConnectedDevice>()
        
        val re = Regex("<ClientInfo>(.*?)</ClientInfo>", RegexOption.DOT_MATCHES_ALL)
        re.findAll(text).forEach { match ->
            val block = match.groupValues[1]
            val mac = find(block, "<MacAddress>([^<]*)</MacAddress>") ?: return@forEach
            val formattedMac = formatMac(normalizeMac(mac) ?: mac)
            out.add(
                ConnectedDevice(
                    name = find(block, "<HostName>([^<]*)</HostName>") ?: vendorFor(mac) ?: "Unknown device",
                    ip = find(block, "<IPAddress>([^<]*)</IPAddress>") ?: "—",
                    mac = formattedMac,
                    online = find(block, "<ConnectionType>([^<]*)</ConnectionType>") != "Disconnected",
                    blocked = formattedMac in blocked,
                    vendor = vendorFor(mac)
                )
            )
        }
        ok(out)
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = io {
        val payload = "<SSID>$ssid</SSID>"
        hnap("SetWLanRadioSettings", payload).toUnit()
    }

    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = io {
        val payload = "<Key>$password</Key>"
        hnap("SetWLanRadioSettings", payload).toUnit()
    }

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val cleanMac = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "<MacAddress>${formatMac(cleanMac)}</MacAddress><Enable>true</Enable>"
        hnap("SetMACFilterSettings", payload).toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val cleanMac = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "<MacAddress>${formatMac(cleanMac)}</MacAddress><Enable>false</Enable>"
        hnap("SetMACFilterSettings", payload).toUnit()
    }

    private suspend fun readMacFilters(): List<MacFilterRule> {
        val text = (hnap("GetMACFilterSettings") as? RouterResult.Ok)?.value ?: return emptyList()
        val out = ArrayList<MacFilterRule>()
        Regex("<MacAddress>([^<]*)</MacAddress>").findAll(text).forEach { m ->
            out.add(MacFilterRule(formatMac(m.groupValues[1]), "", true))
        }
        return out
    }

    override suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> = io { ok(readMacFilters()) }

    override suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> = io {
        val cleanMac = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "<MacAddress>${formatMac(cleanMac)}</MacAddress><Enable>$enabled</Enable>"
        hnap("SetMACFilterSettings", payload).toUnit()
    }

    override suspend fun removeMacFilter(mac: String): RouterResult<Unit> = unblockDevice(mac)

    override suspend fun reboot(): RouterResult<Unit> = io {
        hnap("Reboot").toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        val text = (hnap("GetDeviceSettings") as? RouterResult.Ok)?.value ?: ""
        ok(
            RouterInfo(
                vendor = "D-Link",
                model = find(text, "<ModelName>([^<]*)</ModelName>") ?: "Unknown Model",
                firmware = find(text, "<FirmwareVersion>([^<]*)</FirmwareVersion>") ?: "Unknown",
            )
        )
    }

    override suspend fun getWanInfo(): RouterResult<WanInfo> = io {
        val text = (hnap("GetExternalConnectionStatus") as? RouterResult.Ok)?.value ?: ""
        ok(
            WanInfo(
                status = if (text.contains("<ConnectionStatus>Connected</ConnectionStatus>")) "Connected" else "Disconnected",
                ip = find(text, "<ExternalIPAddress>([^<]*)</ExternalIPAddress>"),
            )
        )
    }
}