package com.example.routermanager.domain

/** Credentials and address the user enters once on the login screen. */
data class RouterCredentials(
    val host: String,
    val username: String,
    val password: String,
)

/** Authenticated session returned by login(). Opaque to the UI. */
data class RouterSession(
    val token: String?,
    val cookies: Map<String, String>,
)

data class NetworkInfo(
    val ssid: String? = null,
    val connected: Boolean = false,
    val clientCount: Int = 0,
    val clientsOnline: Int = 0,
    val clientsTotal: Int = 0,
    val guestSsid: String? = null,
    val guestEnabled: Boolean = false,
    val wan: WanInfo? = null,
)

data class WanInfo(
    val status: String,
    val ip: String? = null,
    val uptimeSeconds: Long? = null,
    val protocol: String? = null,
    val dns: List<String> = emptyList(),
    val signalDbm: Int? = null,
)

data class ConnectedDevice(
    val name: String,
    val ip: String,
    val mac: String,
    val online: Boolean = true,
    val blocked: Boolean = false,
    val hostname: String? = null,
    val vendor: String? = null,
    val interfaceName: String? = null,
    val rssi: Int? = null,
    val connectionType: String? = null,
)

data class MacFilterRule(
    val mac: String,
    val description: String,
    val enabled: Boolean,
)

data class GuestNetwork(
    val band: String,
    val ssid: String,
    val enabled: Boolean,
)

data class RadioState(
    val band: String,
    val enabled: Boolean,
    val channel: String,
    val mode: String,
    val txPower: String,
)

data class RouterInfo(
    val vendor: String,
    val model: String,
    val firmware: String,
    val serial: String? = null,
    val hardwareVersion: String? = null,
    val uptimeSeconds: Long? = null,
    val cpuLoad: Float? = null,
    val memTotalKb: Long? = null,
    val memFreeKb: Long? = null,
    val wanMac: String? = null,
)

data class RouterCapabilities(
    val changeSsid: Boolean = false,
    val changePassword: Boolean = false,
    val listDevices: Boolean = false,
    val blockDevice: Boolean = false,
    val unblockDevice: Boolean = false,
    val macFilter: Boolean = false,
    val guestNetwork: Boolean = false,
    val reboot: Boolean = false,
    val routerInfo: Boolean = false,
    val wanInfo: Boolean = false,
    val parentalControl: Boolean = false,
    val qos: Boolean = false,
    val firmwareUpgrade: Boolean = false,
    val trafficStats: Boolean = false,
)

data class TrafficSample(
    val rxBytes: Long,
    val txBytes: Long,
)

sealed interface RouterResult<out T> {
    data class Ok<T>(val value: T) : RouterResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : RouterResult<Nothing>
}

// ========== نموذج كرت الإنترنت الجديد ==========
data class Voucher(
    val code: String,
    val macAddress: String,
    val deviceName: String,
    val expiryTime: Long,
    val durationText: String
)