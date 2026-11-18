package com.example.routermanager.data

import java.util.Locale

internal data class RouterFingerprintMatch(
    val vendor: String,
    val confidence: Int,
    val evidence: List<String>
)

/**
 * Pure, read-only vendor fingerprint matching. Weak and ambiguous matches are rejected
 * so they cannot trigger a login attempt against the wrong vendor adapter.
 */
internal object RouterFingerprintMatcher {
    private data class VendorFingerprint(
        val vendor: String,
        val signatures: List<Pair<String, Int>>
    )

    private val fingerprints = listOf(
        VendorFingerprint("ZTE", listOf(
            "zxhn" to 100,
            "zte corporation" to 95,
            "getpage.gch" to 90,
            "_sessiontmptoken" to 90
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

    fun match(value: String): RouterFingerprintMatch? {
        val searchable = value.lowercase(Locale.ROOT)
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

        val winner = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: 0

        if (winner.second < MIN_CONFIDENCE ||
            (runnerUp >= MIN_CONFIDENCE && winner.second - runnerUp < AMBIGUITY_MARGIN)
        ) {
            return null
        }

        return RouterFingerprintMatch(
            vendor = winner.first.vendor,
            confidence = winner.second.coerceAtMost(100),
            evidence = winner.third
        )
    }

    private const val MIN_CONFIDENCE = 65
    private const val AMBIGUITY_MARGIN = 15
}
