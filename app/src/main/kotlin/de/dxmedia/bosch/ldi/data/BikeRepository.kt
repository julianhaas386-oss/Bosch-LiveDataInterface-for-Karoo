package de.dxmedia.bosch.ldi.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BikeRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bikes_data",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getProfiles(): List<BikeProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            BikeProfile.deserialize(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize profiles — resetting", e)
            prefs.edit().remove(KEY_PROFILES).apply()
            emptyList()
        }
    }

    fun getActiveProfile(): BikeProfile? = getProfiles().firstOrNull { it.isActive }

    fun upsert(profile: BikeProfile) {
        val profiles = getProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        save(profiles)
    }

    fun delete(id: String) {
        save(getProfiles().filter { it.id != id })
    }

    fun setActive(id: String) {
        save(getProfiles().map { it.copy(isActive = it.id == id) })
    }

    private fun save(profiles: List<BikeProfile>) {
        prefs.edit().putString(KEY_PROFILES, BikeProfile.serialize(profiles)).apply()
    }

    companion object {
        private const val TAG = "BikeRepository"
        private const val KEY_PROFILES = "profiles"
    }
}
