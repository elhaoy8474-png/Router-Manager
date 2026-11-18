package com.example.routermanager.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Desktop User-Agent so routers that reject mobile browsers serve their normal pages. */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/** In-memory cookie store scoped to one router session. */
class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { new ->
            list.removeAll { it.name == new.name }
            list.add(new)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host].orEmpty().filter { it.matches(url) }

    @Synchronized
    fun snapshot(host: String): Map<String, String> =
        store[host].orEmpty().associate { it.name to it.value }

    @Synchronized
    fun clear() = store.clear()
}

/**
 * Builds the OkHttp client used for router traffic.
 * Self-signed certificates are accepted on purpose: home routers rarely have trusted certs,
 * and the LAN address is the only target this client talks to.
 */
object RouterHttpClient {
    val cookieJar = MemoryCookieJar()

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .addInterceptor { chain: okhttp3.Interceptor.Chain ->
            val req: Request = chain.request().newBuilder()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .build()
            val response: Response = chain.proceed(req)
            response
        }
        .build()

    /** Builds an absolute URL from a host the user typed (IP, hostname, optional scheme). */
    fun baseUrl(host: String, https: Boolean = false): HttpUrl {
        val trimmed = host.trim().removeSuffix("/")
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            (if (https) "https://" else "http://") + trimmed
        }
        return withScheme.toHttpUrl()
    }
}
