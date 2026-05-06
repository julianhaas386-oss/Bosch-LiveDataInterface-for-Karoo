package de.dxmedia.bosch.ldi.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BikeRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bikes_data",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getProfiles(): List<BikeProfile> {
        val json = prefs.getString(KEY_PROFILES, null)
        val saved = if (json != null) {
            try {
                BikeProfile.deserialize(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize profiles — resetting", e)
                prefs.edit().remove(KEY_PROFILES).apply()
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
    }
}
