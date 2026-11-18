package com.example.routermanager.network

import com.example.routermanager.domain.RouterCapabilities

/**
 * Pre-built profiles for the generic adapter.
 *
 * Egyptian ISPs (WE, Etisalat, Orange, Vodafone) ship branded Huawei/ZTE ONUs that share
 * one web UI family, so a handful of profiles covers the majority of home routers here.
 * Each profile is a best-effort mapping; a firmware that answers differently reports an
 * error rather than silently doing nothing.
 */
object RouterProfiles {

    /** Huawei EchoLife / HG8245 family (WE / Etisalat ONUs). */
    val HUAWEI = GenericWebApi.Profile(
        capabilities = RouterCapabilities(
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
        ),
        loginPath = "/cgi-bin/index.cgi",
        loginFields = listOf("Username" to "admin", "Password" to "admin"),
        loginSuccessMarker = "menu",
        devicesPath = "/html/network/device.html",
        devicesTableRegex = "([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})[^<]*</td>\\s*<td>\\s*([0-9.]+)[^<]*</td>\\s*<td>\\s*([^<]*)</td>",
        ssidPath = "/html/wlan/wlan.html",
        ssidField = "WLAN_SSID",
        passwordPath = "/html/wlan/wlan.html",
        passwordField = "WLAN_WPA_PSK",
        blockPath = "/html/network/device.html",
        blockField = "MAC",
        rebootPath = "/html/management/device.html",
        statusPath = "/html/status/deviceinfo.html",
        macFilterPath = "/html/network/macfilter.html",
        guestPath = "/html/wlan/wlanguest.html",
    )

    /** ZTE Modern (HTTPS & Session-based). */
    val ZTE_MODERN = GenericWebApi.Profile(
        capabilities = RouterCapabilities(
            listDevices = true, reboot = true, routerInfo = true
        ),
        loginPath = "/index.html",
        loginFields = listOf("Username" to "admin", "Password" to "admin"),
        loginSuccessMarker = "SID",
        devicesPath = "/html/network/device.html",
        devicesTableRegex = "([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})",
    )

    /** Totolink (budget routers, common in Egypt). */
    val TOTOLINK = GenericWebApi.Profile(
        capabilities = RouterCapabilities(
            changeSsid = true,
            changePassword = true,
            listDevices = true,
            blockDevice = true,
            unblockDevice = true,
            macFilter = true,
            reboot = true,
            routerInfo = true,
        ),
        loginPath = "/cgi-bin/luci",
        loginFields = listOf("operation" to "login"),
        loginSuccessMarker = "stok",
        devicesPath = "/cgi-bin/luci/admin/network?form=client",
        devicesTableRegex = "\"mac\"\\s*:\\s*\"([0-9a-fA-F:]{17})\"[^}]*?\"ip\"\\s*:\\s*\"([0-9.]+)\"[^}]*?\"hostname\"\\s*:\\s*\"([^\"]*)\"",
        ssidPath = "/cgi-bin/luci/admin/wireless?form=wlan_2g",
        ssidField = "ssid",
        passwordPath = "/cgi-bin/luci/admin/wireless?form=wlan_2g",
        passwordField = "key",
        blockPath = "/cgi-bin/luci/admin/macfilter?form=blacklist",
        blockField = "mac",
        rebootPath = "/cgi-bin/luci/admin/system?form=reboot",
        statusPath = "/cgi-bin/luci/admin/status?form=all",
        macFilterPath = "/cgi-bin/luci/admin/macfilter?form=blacklist",
    )

    /** Tenda (also rebadged as many local brands). */
    val TENDA = GenericWebApi.Profile(
        capabilities = RouterCapabilities(
            changeSsid = true,
            changePassword = true,
            listDevices = true,
            blockDevice = true,
            unblockDevice = true,
            macFilter = true,
            reboot = true,
            routerInfo = true,
        ),
        loginPath = "/login.cgi",
        loginFields = listOf("action" to "login"),
        loginSuccessMarker = "success",
        devicesPath = "/device.html",
        devicesTableRegex = "([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})[^<]*</td>\\s*<td>\\s*([0-9.]+)[^<]*</td>",
        ssidPath = "/wifi.html",
        ssidField = "ssid",
        passwordPath = "/wifi.html",
        passwordField = "passphrase",
        blockPath = "/blacklist.html",
        blockField = "mac",
        rebootPath = "/reboot.html",
        statusPath = "/status.html",
        macFilterPath = "/blacklist.html",
    )

    /** Mercusys / generic MediaTek SDK web UI (very common on Egyptian ISP plans). */
    val MERCUSYS = GenericWebApi.Profile(
        capabilities = RouterCapabilities(
            changeSsid = true,
            changePassword = true,
            listDevices = true,
            blockDevice = true,
            unblockDevice = true,
            macFilter = true,
            reboot = true,
            routerInfo = true,
        ),
        loginPath = "/cgi-bin/luci",
        loginFields = listOf("operation" to "login"),
        loginSuccessMarker = "stok",
        devicesPath = "/cgi-bin/luci/admin/network?form=client",
        devicesTableRegex = "\"mac\"\\s*:\\s*\"([0-9a-fA-F:]{17})\"[^}]*?\"ip\"\\s*:\\s*\"([0-9.]+)\"",
        ssidPath = "/cgi-bin/luci/admin/wireless?form=wlan_2g",
        ssidField = "ssid",
        passwordPath = "/cgi-bin/luci/admin/wireless?form=wlan_2g",
        passwordField = "key",
        blockPath = "/cgi-bin/luci/admin/macfilter?form=blacklist",
        blockField = "mac",
        rebootPath = "/cgi-bin/luci/admin/system?form=reboot",
        statusPath = "/cgi-bin/luci/admin/status?form=all",
    )
}