package com.example.routermanager.domain

/**
 * Standard operations every router brand implements.
 * Operations a model does not support return RouterResult.Error with a clear message.
 */
interface RouterApi {
    val brand: String
    val capabilities: RouterCapabilities

    suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession>
    suspend fun getNetworkInfo(): RouterResult<NetworkInfo>
    suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>>
    suspend fun changeSsid(ssid: String, band: String? = null): RouterResult<Unit>
    suspend fun changeWifiPassword(password: String, band: String? = null): RouterResult<Unit>
    suspend fun blockDevice(mac: String): RouterResult<Unit>
    suspend fun unblockDevice(mac: String): RouterResult<Unit>
    suspend fun reboot(): RouterResult<Unit>
    suspend fun getRouterInfo(): RouterResult<RouterInfo>

    /** Optional: MAC filter rules, in addition to simple block/unblock. */
    suspend fun getMacFilters(): RouterResult<List<MacFilterRule>> =
        RouterResult.Error("MAC filter list is not supported for $brand.")

    suspend fun setMacFilter(mac: String, description: String, enabled: Boolean): RouterResult<Unit> =
        RouterResult.Error("MAC filter editing is not supported for $brand.")

    suspend fun removeMacFilter(mac: String): RouterResult<Unit> =
        RouterResult.Error("MAC filter removal is not supported for $brand.")

    /** Optional: guest Wi-Fi. */
    suspend fun getGuestNetworks(): RouterResult<List<GuestNetwork>> =
        RouterResult.Error("Guest Wi-Fi is not supported for $brand.")

    suspend fun setGuestNetwork(band: String, enabled: Boolean, ssid: String): RouterResult<Unit> =
        RouterResult.Error("Guest Wi-Fi is not supported for $brand.")

    /** Optional: WAN / internet state. */
    suspend fun getWanInfo(): RouterResult<WanInfo> =
        RouterResult.Error("WAN status is not supported for $brand.")

    /** Optional: reboot a single Wi-Fi radio. */
    suspend fun restartWifi(): RouterResult<Unit> =
        RouterResult.Error("Wi-Fi restart is not supported for $brand.")
        
    /** Optional: Change DNS. */
    suspend fun changeDns(primary: String, secondary: String): RouterResult<Unit> =
        RouterResult.Error("Change DNS is not supported for $brand.")
}