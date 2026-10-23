package io.github.nezdelion.dlnatvcast.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * CPU and Wi-Fi wake locks held while a cast session is active, so serving and polling go on
 * with the screen off. [hold] is idempotent and safe to call on every state change.
 */
class AwakeLocks(context: Context) {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DlnaTvCast:casting")
        .apply { setReferenceCounted(false) }

    private val wifiLock = context.getSystemService(WifiManager::class.java)
        .createWifiLock(wifiMode(), "DlnaTvCast:casting")
        .apply { setReferenceCounted(false) }

    fun hold(on: Boolean) {
        if (on) {
            // Generous upper bound; released explicitly when the session ends.
            if (!wakeLock.isHeld) wakeLock.acquire(MAX_HOLD_MS)
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    private companion object {
        const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L

        // HIGH_PERF is a no-op from Android 14; LOW_LATENCY exists since Android 10 (minSdk).
        @Suppress("DEPRECATION")
        fun wifiMode(): Int =
            if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
