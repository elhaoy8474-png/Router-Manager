package com.example.routermanager.network

import com.example.routermanager.domain.*
import org.json.JSONObject

class SmartRouterApi(private val profile: JSONObject) : BaseRouterApi() {
    override val brand = "SmartRouter"
    override val capabilities = RouterCapabilities()

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> {
        base = RouterHttpClient.baseUrl(credentials.host)
        return postForm(profile.getString("login_path"), mapOf("Username" to credentials.username, "Password" to credentials.password))
            .let { if (it is RouterResult.Ok) RouterResult.Ok(RouterSession("", emptyMap())) else RouterResult.Error("Login failed") }
    }

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = RouterResult.Ok(emptyList())
    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = RouterResult.Error("Not implemented")
    override suspend fun blockDevice(mac: String): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = RouterResult.Error("Not implemented")
    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun restartWifi(): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun reboot(): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> = RouterResult.Error("Not implemented")
    override suspend fun setMacFilter(mac: String, desc: String, enabled: Boolean): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun removeMacFilter(mac: String): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun getGuestNetworks(): RouterResult<List<GuestNetwork>> = RouterResult.Error("Not implemented")
    override suspend fun setGuestNetwork(band: String, enabled: Boolean, ssid: String): RouterResult<Unit> = RouterResult.Error("Not implemented")
    override suspend fun getWanInfo(): RouterResult<WanInfo> = RouterResult.Error("Not implemented")
    override suspend fun changeDns(primary: String, secondary: String): RouterResult<Unit> = RouterResult.Error("Not implemented")
}