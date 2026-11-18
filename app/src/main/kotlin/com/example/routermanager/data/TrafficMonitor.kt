package com.example.routermanager.data

import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

class TrafficMonitor {

    // دالة تراقب السرعة وتُرسل النتيجة كل ثانية
    fun getTrafficFlow() = flow {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastTime = System.currentTimeMillis()

        while (true) {
            delay(1000) // كل ثانية

            val currentTime = System.currentTimeMillis()
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()

            // التأكد من أن الجهاز يدعم قراءة البيانات
            if (currentRx != TrafficStats.UNSUPPORTED.toLong() && lastRx != TrafficStats.UNSUPPORTED.toLong()) {
                val timeDiff = (currentTime - lastTime) / 1000f
                val rxDiff = currentRx - lastRx
                val txDiff = currentTx - lastTx

                val downSpeed = (rxDiff / timeDiff) / 1024f // تحويل إلى كيلوبايت/ث
                val upSpeed = (txDiff / timeDiff) / 1024f   // تحويل إلى كيلوبايت/ث

                emit(Pair(downSpeed, upSpeed))
            }
            lastRx = currentRx
            lastTx = currentTx
            lastTime = currentTime
        }
    }
}