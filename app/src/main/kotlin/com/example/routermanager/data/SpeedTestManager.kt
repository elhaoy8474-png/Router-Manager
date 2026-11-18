package com.example.routermanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SpeedTestManager {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Measures HTTPS response latency against Cloudflare; this is not ICMP ping. */
    suspend fun measurePing(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val times = mutableListOf<Long>()
            repeat(4) {
                val start = System.currentTimeMillis()
                val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=10").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) times.add(System.currentTimeMillis() - start)
                }
            }
            if (times.isEmpty()) return@withContext Pair(-1L, -1L)
            
            val ping = times.average().toLong()
            // حساب التذبذب (الفرق بين أقل وأعلى بينج)
            val jitter = if (times.size > 1) (times.maxOrNull()!! - times.minOrNull()!!) else 0L
            
            return@withContext Pair(ping, jitter)
        } catch (e: Exception) {
            return@withContext Pair(-1L, -1L)
        }
    }

    /** يقيس سرعة التحميل الحية (يرسل السرعة كل 200 ملي ثانية) */
    suspend fun measureDownloadLive(onProgress: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        try {
            val bytesToDownload = 50_000_000L // 50 ميجا
            val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=$bytesToDownload").build()

            var totalBytesRead = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime
            var lastBytes = 0L

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext -1.0
                
                val buffer = ByteArray(8192)
                response.body?.byteStream()?.use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalBytesRead += read
                        val currentTime = System.currentTimeMillis()
                        
                        // تحديث الواجهة كل 200 ملي ثانية
                        if (currentTime - lastUpdateTime > 200) {
                            val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                            val bytesDiff = totalBytesRead - lastBytes
                            
                            // حساب السرعة اللحظية (ميجابت/ثانية)
                            val currentSpeed = ((bytesDiff * 8) / timeDiff) / 1_000_000.0
                            onProgress(currentSpeed)
                            
                            lastUpdateTime = currentTime
                            lastBytes = totalBytesRead
                            
                            // إيقاف الاختبار بعد 15 ثانية لتوفير بيانات المستخدم
                            if (currentTime - startTime > 15000) break
                        }
                    }
                }
            }
            
            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000.0
            return@withContext ((totalBytesRead * 8) / totalElapsed) / 1_000_000.0
        } catch (e: Exception) {
            return@withContext -1.0
        }
    }

    /** يقيس سرعة الرفع الحية */
    suspend fun measureUploadLive(onProgress: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        try {
            val bytesToUpload = 20_000_000L // 20 ميجا
            val payload = ByteArray(bytesToUpload.toInt())
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            
            val request = Request.Builder().url("https://speed.cloudflare.com/__up").post(body).build()

            val startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext -1.0
            }

            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val finalSpeed = ((bytesToUpload * 8) / totalElapsed) / 1_000_000.0
            
            onProgress(finalSpeed) // تحديث أخير
            return@withContext finalSpeed
        } catch (e: Exception) {
            return@withContext -1.0
        }
    }
}