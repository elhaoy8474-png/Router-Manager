package com.example.routermanager.data

import android.content.Context
import com.example.routermanager.domain.*
import com.example.routermanager.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

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

    suspend fun connect(credentials: RouterCredentials, preferredBrand: String): RouterResult<Pair<RouterApi, RouterSession>> {
        val candidates: List<RouterApi> = if (preferredBrand == "Auto") {
            // نتعرف على الماركة من صفحة الراوتر نفسها بدل ما نبعت الباسورد لكل الماركات
            val hint = detectBrandHint(credentials.host)
            val matched = if (hint == null) emptyList() else apis.filter { it.brand.contains(hint, ignoreCase = true) }
            if (matched.isNotEmpty()) matched else apis
        } else {
            // ماركة محددة: نجرب هي بس ونعرض خطأها الحقيقي
            apis.filter { it.brand == preferredBrand }.ifEmpty { apis }
        }

        val errors = mutableListOf<Pair<String, RouterResult.Error>>()

        for (api in candidates) {
            val result = api.login(credentials)
            if (result is RouterResult.Ok) {
                return RouterResult.Ok(Pair(api, result.value))
            } else if (result is RouterResult.Error) {
                errors.add(api.brand to result)
            }
        }

        if (errors.isEmpty()) return RouterResult.Error("No supported router found.")
        if (errors.size == 1) return errors.first().second

        // Auto بدون تعرّف: نعرض أخطاء كل الماركات عشان نعرف أي واحدة وصلت للراوتر فعلاً
        val summary = errors.joinToString("\n") { "${it.first}: ${it.second.message}" }
        return RouterResult.Error(summary)
    }

    /**
     * يفتح صفحة الراوتر (HTTPS ثم HTTP) ويدوّر على علامات الماركة في الـ Server header أو الصفحة.
     * بيرجع null لو مش متأكد، وساعتها نرجع لتجربة كل الماركات.
     */
    private suspend fun detectBrandHint(host: String): String? = withContext(Dispatchers.IO) {
        for (https in listOf(true, false)) {
            try {
                val url = RouterHttpClient.baseUrl(host, https)
                val request = Request.Builder().url(url).get().build()
                RouterHttpClient.client.newCall(request).execute().use { response ->
                    val server = response.header("Server").orEmpty()
                    val body = response.body?.string().orEmpty()
                    if (server.contains("ZTE", ignoreCase = true) ||
                        body.contains("ZXHN", ignoreCase = true) ||
                        body.contains("ZTE Corporation", ignoreCase = true)
                    ) {
                        return@withContext "ZTE"
                    }
                }
                return@withContext null
            } catch (e: Exception) {
                // الاتصال فشل على الـ scheme ده، نجرب التاني
            }
        }
        null
    }

    suspend fun detectWithScore(host: String): Pair<RouterApi, Int>? {
        val hint = detectBrandHint(host) ?: return null
        val api = apis.firstOrNull { it.brand.contains(hint, ignoreCase = true) } ?: return null
        return Pair(api, 100)
    }
}
