package io.github.nezdelion.dlnatvcast.prefs

import android.content.Context
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice

/** Remembers the last renderer (UDN + description URL) and manually added description URLs. */
class RendererStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("renderers", Context.MODE_PRIVATE)

    data class Remembered(val udn: String, val location: String, val name: String)

    val last: Remembered?
        get() {
            val udn = prefs.getString(KEY_UDN, null) ?: return null
            val location = prefs.getString(KEY_LOCATION, null) ?: return null
            return Remembered(udn, location, prefs.getString(KEY_NAME, null) ?: udn)
        }

    fun remember(device: RendererDevice) {
        prefs.edit()
            .putString(KEY_UDN, device.udn)
            .putString(KEY_LOCATION, device.location)
            .putString(KEY_NAME, device.friendlyName)
            .apply()
    }

    val manualLocations: Set<String> get() = prefs.getStringSet(KEY_MANUAL, emptySet()).orEmpty()

    fun addManual(location: String) {
        prefs.edit().putStringSet(KEY_MANUAL, manualLocations + location).apply()
    }

    fun removeManual(location: String) {
        prefs.edit().putStringSet(KEY_MANUAL, manualLocations - location).apply()
    }

    private companion object {
        const val KEY_UDN = "last_udn"
        const val KEY_LOCATION = "last_location"
        const val KEY_NAME = "last_name"
        const val KEY_MANUAL = "manual_locations"
    }
}
