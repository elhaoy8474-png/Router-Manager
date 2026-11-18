package com.example.routermanager.data

import android.content.Context
import com.example.routermanager.domain.*
import com.example.routermanager.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Chooses one router adapter only after identifying the management UI.
 *
 * Auto mode is identification-first. An unknown fingerprint is reported as unknown
 * instead of being treated as a reason to send credentials to every vendor adapter.
 */
class RouterFactory(private val context: Context) {

    private val apis: List<RouterApi> = listOf(
        ZteApi(),
        HuaweiApi(),
        TpLinkApi(),
        DLinkApi(),
        AsusApi(),
        NetgearApi(),
        XiaomiApi()
    )

    val supportedBrands: List<String> = listOf("Auto") + apis.map { it.brand }

    private val probeClient by lazy {
        RouterHttpClient.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            // Discovery must not follow a router redirect to an unrelated host.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class RouterIdentification(
        val vendor: String,
        val model: String?,
        val firmware: String?,
        val confidence: Int,
        val evidence: List<String>
    )

    private data class VendorFingerprint(
        val vendor: String,
        val signatures: List<Pair<String, Int>>
    )

    private val fingerprints = listOf(
        VendorFingerprint("ZTE", listOf(
            "zxhn" to 100,
            "zte corporation" to 95,
            "getpage.gch" to 90,
            "_sessiontmptoken" to 90,
        )),
        VendorFingerprint("Huawei", listOf(
            "echolife" to 100,
            "x_hw_token" to 95,
            "getrandcount.asp" to 90,
            "huawei technologies" to 95
        )),
        VendorFingerprint("TP-Link", listOf(
            "tp-link" to 100,
            "tplink" to 95,
            "tplogin" to 90,
            "/webpages/" to 55
        )),
        VendorFingerprint("D-Link", listOf(
            "d-link" to 100,
            "dlink" to 90,
            "hnap1" to 80
        )),
        VendorFingerprint("ASUS", listOf(
            "asuswrt" to 100,
            "router.asus.com" to 95,
            "asuswrt-merlin" to 95
        )),
        VendorFingerprint("Netgear", listOf(
            "netgear" to 100,
            "unauth.cgi" to 85,
            "genie" to 55
        )),
        VendorFingerprint("Xiaomi", listOf(
            "miwifi" to 100,
            "xqsystem" to 90,
            "xiaomi" to 85
        ))
    )

    suspend fun connect(
        credentials: RouterCredentials,
        preferredBrand: String
    ): RouterResult<Pair<RouterApi, RouterSession>> {
        val api: RouterApi
        val identification: RouterIdentification?

        if (preferredBrand.equals("Auto", ignoreCase = true)) {
            identification = identifyRouter(credentials.host)
                ?: return RouterResult.Error(
                    "لم أستطع تحديد نوع واجهة الراوتر تلقائيًا. لم أرسل كلمة المرور لأي شركة. " +
                        "جرّب فتح صفحة إدارة الراوتر من الهاتف، أو اختر الشركة يدويًا مؤقتًا."
                )

            api = apis.firstOrNull {
                it.brand.contains(identification.vendor, ignoreCase = true)
            } ?: return RouterResult.Error(
                "تم التعرف على " + identification.vendor +
                    (identification.model?.let { " $it" } ?: "") +
                    "، لكن لا يوجد محول اتصال جاهز لهذا الطراز حتى الآن."
            )
        } else {
            api = apis.firstOrNull { it.brand == preferredBrand }
                ?: return RouterResult.Error("لا يوجد محول اتصال مسجل للشركة المختارة: " + preferredBrand)
            identification = null
        }

        return when (val result = api.login(credentials)) {
            is RouterResult.Ok -> RouterResult.Ok(Pair(api, result.value))
            is RouterResult.Error -> {
                val detected = identification?.let {
                    "تم التعرف على " + it.vendor +
                        (it.model?.let { model -> " $model" } ?: "") +
                        " بدرجة ثقة " + it.confidence + "%، "
                }.orEmpty()
                RouterResult.Error(detected + "لكن تسجيل الدخول عبر محول " + api.brand + " فشل: " + result.message, result.cause)
            }
        }
    }

    /**
     * Read-only identification: GET the router landing page over the two usual schemes.
     * No username/password is sent during this stage.
     */
    suspend fun identifyRouter(host: String): RouterIdentification? = withContext(Dispatchers.IO) {
        for (https in listOf(true, false)) {
            val url = runCatching { RouterHttpClient.baseUrl(host, https) }.getOrNull() ?: continue
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "close")
                    .get()
                    .build()

                probeClient.newCall(request).execute().use { response ->
                    // Do not identify a vendor from a generic 3xx/404/error template.
                    if (response.code !in setOf(200, 401, 403)) return@use
                    val server = response.header("Server").orEmpty()
                    val poweredBy = response.header("X-Powered-By").orEmpty()
                    val auth = response.header("WWW-Authenticate").orEmpty()
                    val contentType = response.header("Content-Type").orEmpty()
                    val body = readBodyPrefix(response)
                    val title = Regex(
                        "<title[^>]*>(.*?)</title>",
                        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                    ).find(body)?.groupValues?.getOrNull(1).orEmpty()

                    // A generic 404 page alone is not a fingerprint.
                    val searchable = listOf(server, poweredBy, auth, contentType, title, body)
                        .joinToString(" ")
                        .lowercase(Locale.ROOT)

                    val ranked = fingerprints.map { fingerprint ->
                        val matches = fingerprint.signatures.filter { (needle, _) ->
                            searchable.contains(needle)
                        }
                        Triple(
                            fingerprint,
                            matches.sumOf { it.second },
                            matches.map { it.first }
                        )
                    }.sortedByDescending { it.second }

                    val winner = ranked.firstOrNull()
                    val runnerUp = ranked.getOrNull(1)?.second ?: 0

                    // A weak or ambiguous match must not cause an authentication attempt.
                    if (winner != null &&
                        winner.second >= MIN_CONFIDENCE &&
                        !(runnerUp >= MIN_CONFIDENCE && winner.second - runnerUp < AMBIGUITY_MARGIN)
                    ) {
                        val model = extractModel(searchable)
                        val firmware = extractFirmware(searchable)
                        val confidence = winner.second.coerceAtMost(100)

                        return@withContext RouterIdentification(
                            vendor = winner.first.vendor,
                            model = model,
                            firmware = firmware,
                            confidence = confidence,
                            evidence = winner.third
                        )
                    }
                }
            } catch (_: Exception) {
                // Try the alternate scheme; never include credentials in discovery requests.
            }
        }
        null
    }

    suspend fun detectWithScore(host: String): Pair<RouterApi, Int>? {
        val identification = identifyRouter(host) ?: return null
        val api = apis.firstOrNull {
            it.brand.contains(identification.vendor, ignoreCase = true)
        } ?: return null
        return Pair(api, identification.confidence)
    }

    /** Read only a bounded prefix; ResponseBody.string() would buffer an unbounded page. */
    private fun readBodyPrefix(response: okhttp3.Response): String {
        val source = response.body?.source() ?: return ""
        val buffer = okio.Buffer()
        var remaining = MAX_FINGERPRINT_BYTES
        while (remaining > 0) {
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
            remaining -= read
        }
        return buffer.readUtf8()
    }

    private fun extractModel(text: String): String? {
        val patterns = listOf(
            Regex("""\bZXHN[\s_-]?[A-Z0-9-]{2,}\b""", RegexOption.IGNORE_CASE),
            Regex("""\bHG\d{4,5}[A-Z0-9-]*\b""", RegexOption.IGNORE_CASE),
            Regex("""\bArcher\s+[A-Z0-9-]+\b""", RegexOption.IGNORE_CASE),
            Regex("""\bDIR-\d{3,4}[A-Z0-9-]*\b""", RegexOption.IGNORE_CASE),
            Regex("""\bRT-[A-Z0-9-]{3,}\b""", RegexOption.IGNORE_CASE),
            Regex("""\bH188[A-Z0-9-]*\b""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.value }
    }

    private fun extractFirmware(text: String): String? {
        val pattern = Regex(
            """(?:firmware|software|fwversion|firmwareversion|version)\s*["'=:\s>]+\s*([a-z0-9][a-z0-9._-]{2,31})""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    private companion object {
        const val MAX_FINGERPRINT_BYTES = 256 * 1024L
        const val MIN_CONFIDENCE = 65
        const val AMBIGUITY_MARGIN = 15
    }
}
