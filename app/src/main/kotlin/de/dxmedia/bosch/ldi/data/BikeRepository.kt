package de.dxmedia.bosch.ldi.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BikeRepository internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(createResilientPrefs(context.applicationContext))

    fun getProfiles(): List<BikeProfile> {
        // Encrypted prefs can throw on READ too (value fails to decrypt even though
        // create() succeeded) — never let that kill the first composition (issue #4).
        val json = try {
            prefs.getString(KEY_PROFILES, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read profiles — using defaults", e)
            null
        }
        val saved = if (json != null) {
            try {
                BikeProfile.deserialize(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize profiles — resetting", e)
                try {
                    prefs.edit().remove(KEY_PROFILES).apply()
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to reset corrupt profiles entry", e2)
                }
                emptyList()
            }
        } else emptyList()
        val bySlot = saved.associateBy { it.slot }
        return BikeSlot.values().map { slot ->
            bySlot[slot] ?: BikeProfile(slot = slot, bleAddress = null, isActive = false)
        }
    }

    fun getActiveProfile(): BikeProfile? = getProfiles().firstOrNull { it.isActive }

    fun upsert(profile: BikeProfile) {
        save(getProfiles().map { if (it.slot == profile.slot) profile else it })
    }

    fun delete(slot: BikeSlot) {
        save(getProfiles().map {
            if (it.slot == slot) BikeProfile(slot = slot, bleAddress = null, isActive = false) else it
        })
    }

    fun setActive(slot: BikeSlot) {
        save(getProfiles().map { it.copy(isActive = it.slot == slot) })
    }

    private fun save(profiles: List<BikeProfile>) {
        try {
            prefs.edit().putString(KEY_PROFILES, BikeProfile.serialize(profiles)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }

    companion object {
        private const val TAG = "BikeRepository"
        private const val KEY_PROFILES = "profiles"
        private const val PREFS_FILE = "bikes_data"
        private const val PREFS_FILE_FALLBACK = "bikes_data_plain"

        /**
         * Opens the bike store, surviving devices whose keystore breaks
         * `EncryptedSharedPreferences` (issue #4: grey start screen on Karoo).
         *
         * 1. Try encrypted prefs.
         * 2. On failure, wipe the (likely corrupt) keyset and retry once.
         * 3. If still failing, fall back to plain prefs so the app always starts.
         */
        private fun createResilientPrefs(context: Context): SharedPreferences =
            resolvePrefs(
                openEncrypted = { createEncryptedPrefs(context) },
                clearCorrupt = { deleteEncryptedPrefs(context) },
                openFallback = {
                    context.getSharedPreferences(PREFS_FILE_FALLBACK, Context.MODE_PRIVATE)
                }
            )

        /** Pure recovery policy, kept side-effect-free for unit testing. */
        internal fun resolvePrefs(
            openEncrypted: () -> SharedPreferences,
            clearCorrupt: () -> Unit,
            openFallback: () -> SharedPreferences
        ): SharedPreferences {
            try {
                return openEncrypted()
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences init failed — clearing keyset and retrying", e)
            }
            try {
                clearCorrupt()
                return openEncrypted()
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted prefs unavailable — falling back to plain SharedPreferences", e)
            }
            return openFallback()
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun deleteEncryptedPrefs(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(PREFS_FILE)
            } else {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
    }
}
