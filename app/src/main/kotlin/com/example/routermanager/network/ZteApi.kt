package com.example.routermanager.network

import com.example.routermanager.domain.*
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

class ZteApi(
    client: okhttp3.OkHttpClient = RouterHttpClient.client,
    cookieJar: MemoryCookieJar = RouterHttpClient.cookieJar,
) : BaseRouterApi(client, cookieJar) {

    override val brand = "ZTE (Orange / WE)"

    override val capabilities = RouterCapabilities(
        changeSsid = false, changePassword = false, listDevices = true,
        blockDevice = true, unblockDevice = true, macFilter = true,
        guestNetwork = false, reboot = true, routerInfo = true, wanInfo = true
    )

    /** Token العمليات بعد تسجيل الدخول (يتحدّث من الصفحة بعد النجاح). */
    private var sessionToken: String? = null

    private class Reply(val code: Int, val body: String)

    private sealed interface Step {
        data class Success(val session: RouterSession) : Step
        /** الراوتر رد ورفض (باسورد غلط / مقفول / فيرموير مش مدعوم) — مفيش فايدة نجرب http. */
        data class Rejected(val message: String) : Step
        /** مفيش اتصال بالبورت ده (نجرب الـ scheme التاني). */
        data class Unreachable(val message: String) : Step
    }

    /**
     * صيغ حساب الباسورد. الأولى هي الأرجح لفيرموير ZTE (H188A)،
     * والباقي احتياطي. العدد محدود عشان ما نقفلش الراوتر بمحاولات فاشلة كتير.
     */
    private val formulas: List<Pair<String, (String, String) -> String>> = listOf(
        "sha256(pass+token)" to { p: String, t: String -> sha256(p + t) },
        "sha256(token+pass)" to { p: String, t: String -> sha256(t + p) },
        "sha256(sha256(pass)+token)" to { p: String, t: String -> sha256(sha256(p) + t) },
    )

    override suspend fun login(credentials: RouterCredentials): RouterResult<RouterSession> = io {
        val unreachable = mutableListOf<String>()
        // الراوتر ده بيفتح HTTPS (بورت 443) بشهادة self-signed، والعميل بيقبلها. HTTP احتياطي.
        for (https in listOf(true, false)) {
            val root = RouterHttpClient.baseUrl(credentials.host, https = https)
            base = root
            when (val step = attempt(credentials, root)) {
                is Step.Success -> return@io ok(step.session)
                is Step.Rejected -> return@io fail(step.message)
                is Step.Unreachable -> unreachable.add(step.message)
            }
        }
        fail("ZTE: cannot reach the router at ${credentials.host}. " + unreachable.joinToString(" | "))
    }

    private fun attempt(c: RouterCredentials, root: HttpUrl): Step {
        return try {
            seedCookies(root)
            val tried = mutableListOf<String>()
            var lastBody = ""
            var sessionTokenFound = false

            for ((index, formula) in formulas.withIndex()) {
                // كل محاولة بتبدأ بصفحة جديدة وtoken جديد (الـ tokens غالباً لمرة واحدة)
                val page = call(root)
                val sess = extractSessionToken(page.body)
                sessionTokenFound = sess != null

                val tokenReply = call(root.resolve(TOKEN_PATH)!!)
                val loginToken = Regex("<ajax_response_xml_root>\\s*([^<]*?)\\s*</ajax_response_xml_root>")
                    .find(tokenReply.body)?.groupValues?.get(1)
                if (loginToken.isNullOrBlank()) {
                    return Step.Rejected(
                        "ZTE: login_token endpoint not found (HTTP ${tokenReply.code}). " +
                            "This firmware is not supported yet. Reply: ${snippet(tokenReply.body)}"
                    )
                }

                val hash = formula.second(c.password, loginToken)
                val form = "Password=$hash&Username=${enc(c.username)}" +
                    "&_sessionTOKEN=${sess.orEmpty()}&action=login"
                val reply = call(root.resolve(ENTRY_PATH)!!, form)
                lastBody = reply.body
                tried.add(formula.first)

                val json = parseJson(reply.body)
                val sessToken = json?.optString("sess_token").orEmpty()
                val success = json != null && (json.optBoolean("login_need_refresh", false) || sessToken.isNotBlank())
                if (success) {
                    // نحدّث الصفحة عشان ناخد _sessionTmpToken الخاص بالجلسة الجديدة
                    val home = runCatching { call(root) }.getOrNull()
                    sessionToken = home?.let { extractSessionToken(it.body) } ?: sessToken.ifBlank { null }
                    return Step.Success(
                        RouterSession(token = sessionToken, cookies = cookieJar.snapshot(root.host))
                    )
                }

                // لو الراوتر قفل تسجيل الدخول مؤقتاً نوقف فوراً — أي محاولة زيادة بتطوّل القفل
                val lockSeconds = json?.optInt("lockingTime", 0) ?: 0
                val lockedText = Regex("too many|account (is )?locked|try again later", RegexOption.IGNORE_CASE)
                    .containsMatchIn(reply.body)
                if (lockSeconds > 0 || lockedText) {
                    return Step.Rejected(
                        "ZTE: the router temporarily locked login (wait a few minutes, then try once). " +
                            "Reply: ${snippet(reply.body)}"
                    )
                }
            }

            Step.Rejected(
                "ZTE: login rejected. Wrong username/password, or the hash formula is not supported. " +
                    "Tried: ${tried.joinToString(", ")}. Session token: ${if (sessionTokenFound) "found" else "MISSING"}. " +
                    "Router reply: ${snippet(lastBody)}"
            )
        } catch (e: IOException) {
            Step.Unreachable("${root.scheme}://${root.host}: ${e.message}")
        }
    }

    // ---------- أدوات مساعدة لتسجيل الدخول ----------

    private fun call(url: HttpUrl, form: String? = null): Reply {
        val origin = "${url.scheme}://${url.host}"
        val builder = Request.Builder().url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", origin)
            .header("Referer", "$origin/")
        if (form != null) {
            builder.post(form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        } else {
            builder.get()
        }
        return client.newCall(builder.build()).execute().use { r ->
            Reply(r.code, r.body?.string().orEmpty())
        }
    }

    /** المتصفح بيضيف الكوكيز دي بجافاسكربت، والراوتر ممكن يرفض الدخول من غيرها. */
    private fun seedCookies(root: HttpUrl) {
        runCatching {
            val cookies = listOf("_TESTCOOKIESUPPORT", "_TESTCOOKIESUPPORT_HTTPS_").map {
                Cookie.Builder().name(it).value("1").domain(root.host).path("/").build()
            }
            cookieJar.saveFromResponse(root, cookies)
        }
    }

    /** يقرأ _sessionTmpToken (مكتوب غالباً كـ \xNN) أو حقل _sessionTOKEN المخفي. */
    private fun extractSessionToken(html: String): String? {
        Regex("_sessionTmpToken\\s*=\\s*([^;\\n]+)").find(html)?.let { m ->
            val pieces = Regex("\"([^\"]*)\"|'([^']*)'").findAll(m.groupValues[1])
                .joinToString("") { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val token = decodeEscapes(pieces)
            if (token.isNotBlank()) return token
        }
        hiddenInput(html, "_sessionTOKEN")?.takeIf { it.isNotBlank() }?.let { return decodeEscapes(it) }
        Regex("[\"']?_sessionTOKEN[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']").find(html)
            ?.let { return decodeEscapes(it.groupValues[1]) }
        return null
    }

    private fun decodeEscapes(s: String): String =
        Regex("""\\x([0-9a-fA-F]{2})|\\u([0-9a-fA-F]{4})""").replace(s) { m ->
            val hex = m.groupValues[1].ifEmpty { m.groupValues[2] }
            hex.toInt(16).toChar().toString()
        }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun snippet(s: String): String = s.replace(Regex("\\s+"), " ").take(220)

    // ---------- باقي الوظائف (لسه على المسارات القديمة، محتاجة تحديث بعد نجاح الدخول) ----------

    override suspend fun getConnectedDevices(): RouterResult<List<ConnectedDevice>> = io {
        // محاولة تجربة مسارات مختلفة حتى نجد المسار الصحيح الذي لا يُرجع 404
        val paths = listOf(
            "/getpage.gch?pid=1002&nextpage=sta_wlan",
            "/getpage.gch?pid=1002&nextpage=device_list",
            "/html/network/device.html",
            "/api/device/list"
        )

        var body = ""
        var found = false
        for (path in paths) {
            val r = get(path)
            if (r is RouterResult.Ok) {
                body = r.value
                found = true
                break
            }
        }

        if (!found) return@io fail("Device list not found (404).")

        val list = ArrayList<ConnectedDevice>()
        val regex = Regex("([0-9A-Fa-f:]{17})")
        regex.findAll(body).forEach { m ->
            list.add(ConnectedDevice(name = "ZTE Device", ip = "Unknown", mac = formatMac(m.groupValues[1])))
        }
        ok(list)
    }

    override suspend fun getNetworkInfo(): RouterResult<NetworkInfo> = io {
        val devices = (getConnectedDevices() as? RouterResult.Ok)?.value.orEmpty()
        ok(NetworkInfo(ssid = "ZTE Wi-Fi", connected = true, clientCount = devices.size, clientsOnline = devices.size, clientsTotal = devices.size))
    }

    override suspend fun blockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "MacAddress=${formatMac(clean)}&Enable=1&Action=Add"
        postBody("/getpage.gch?pid=1002&nextpage=net_macfilter.asp", payload, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun unblockDevice(mac: String): RouterResult<Unit> = io {
        val clean = normalizeMac(mac) ?: return@io fail("Invalid MAC")
        val payload = "MacAddress=${formatMac(clean)}&Enable=0&Action=Add"
        postBody("/getpage.gch?pid=1002&nextpage=net_macfilter.asp", payload, "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun reboot(): RouterResult<Unit> = io {
        postBody("/getpage.gch?pid=1002&nextpage=manager_dev_conf_t.gch", "Action=Reboot", "application/x-www-form-urlencoded").toUnit()
    }

    override suspend fun getRouterInfo(): RouterResult<RouterInfo> = io {
        ok(RouterInfo(vendor = "ZTE", model = "ZXHN H188A", firmware = "Unknown"))
    }

    override suspend fun changeSsid(ssid: String, band: String?): RouterResult<Unit> = notSupported("Change SSID")
    override suspend fun changeWifiPassword(password: String, band: String?): RouterResult<Unit> = notSupported("Change Password")
    override suspend fun restartWifi(): RouterResult<Unit> = notSupported("Restart Wi-Fi")

    private companion object {
        const val TOKEN_PATH = "/?_type=loginData&_tag=login_token"
        const val ENTRY_PATH = "/?_type=loginData&_tag=login_entry"
    }
}
