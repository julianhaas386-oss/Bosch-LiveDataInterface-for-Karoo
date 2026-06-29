package de.dxmedia.bosch.ldi.util

import android.content.Context

/**
 * Persisted developer toggles, stored in the same plain prefs file as [LocaleHelper]
 * (no encryption needed — these are not secrets and must survive a keystore failure).
 */
object DebugSettings {
    private const val PREFS = "app_prefs"
    private const val KEY_BLE_DEBUG = "ble_debug_enabled"

    fun isBleDebugEnabled(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BLE_DEBUG, false)
        }.getOrDefault(false)

    fun setBleDebugEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BLE_DEBUG, enabled)
                .apply()
        }
    }
}
