package com.example.netyantra.portal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Repository for managing captive portal profiles.
 * Passwords and profile configurations are stored using [EncryptedSharedPreferences]
 * backed by the hardware-backed Android Keystore (AES256-GCM / AES256-SIV).
 */
class PortalProfileRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = createEncryptedPrefs()

    private val _profilesFlow = MutableStateFlow<List<PortalProfile>>(emptyList())
    val profilesFlow: StateFlow<List<PortalProfile>> = _profilesFlow.asStateFlow()

    init {
        refreshProfiles()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // In case of keystore key invalidation/corruption, clear and re-create safely
            appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun refreshProfiles() {
        val idSet = securePrefs.getStringSet(KEY_PROFILE_IDS, emptySet()) ?: emptySet()
        val list = mutableListOf<PortalProfile>()

        for (id in idSet) {
            val jsonStr = securePrefs.getString(getProfileDataKey(id), null)
            val password = securePrefs.getString(getPasswordKey(id), "") ?: ""
            if (jsonStr != null) {
                val profile = PortalProfile.fromJson(jsonStr, passwordOverride = password)
                if (profile != null) {
                    list.add(profile)
                }
            }
        }

        // Sort by SSID for consistent UI display
        _profilesFlow.value = list.sortedBy { it.ssid.lowercase() }
    }

    fun getAllProfiles(): List<PortalProfile> {
        return _profilesFlow.value
    }

    fun getProfile(id: String): PortalProfile? {
        val jsonStr = securePrefs.getString(getProfileDataKey(id), null) ?: return null
        val password = securePrefs.getString(getPasswordKey(id), "") ?: ""
        return PortalProfile.fromJson(jsonStr, passwordOverride = password)
    }

    fun getProfileForSsid(ssid: String?): PortalProfile? {
        val profiles = _profilesFlow.value
        if (profiles.isEmpty()) return null

        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
            // When SSID cannot be retrieved (e.g. location permission not yet granted on Android 10+),
            // fallback to the saved profile if exactly one exists.
            return if (profiles.size == 1) profiles.first() else null
        }

        val trimmed = ssid.trim().removeSurrounding("\"")
        return profiles.firstOrNull {
            it.ssid.trim().removeSurrounding("\"").equals(trimmed, ignoreCase = true)
        } ?: if (profiles.size == 1) profiles.first() else null
    }

    fun hasProfileForSsid(ssid: String?): Boolean {
        return getProfileForSsid(ssid) != null
    }

    /**
     * Saves or updates a captive portal profile.
     * The password is explicitly stored in [EncryptedSharedPreferences] backed by Android Keystore.
     */
    fun saveProfile(profile: PortalProfile) {
        val currentIds = securePrefs.getStringSet(KEY_PROFILE_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentIds.add(profile.id)

        // Store profile metadata without plaintext password in one key,
        // and store the password strictly in its own encrypted key
        val profileJson = profile.toJson(includePassword = false).toString()

        securePrefs.edit()
            .putStringSet(KEY_PROFILE_IDS, currentIds)
            .putString(getProfileDataKey(profile.id), profileJson)
            .putString(getPasswordKey(profile.id), profile.password)
            .putString(getSsidIndexKey(profile.ssid), profile.id)
            .apply()

        refreshProfiles()
    }

    /**
     * Deletes a profile by its ID, erasing all associated credentials.
     */
    fun deleteProfile(id: String): Boolean {
        val profile = getProfile(id)
        val currentIds = securePrefs.getStringSet(KEY_PROFILE_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val existed = currentIds.remove(id)

        val editor = securePrefs.edit()
            .putStringSet(KEY_PROFILE_IDS, currentIds)
            .remove(getProfileDataKey(id))
            .remove(getPasswordKey(id))

        if (profile != null) {
            editor.remove(getSsidIndexKey(profile.ssid))
        }
        editor.apply()

        refreshProfiles()
        return existed
    }

    /**
     * Deletes saved login and credentials associated with a specific SSID.
     * Fulfills the "delete saved login" requirement.
     */
    fun deleteSavedLoginForSsid(ssid: String): Boolean {
        val profile = getProfileForSsid(ssid) ?: return false
        return deleteProfile(profile.id)
    }

    companion object {
        private const val PREFS_FILE_NAME = "netyantra_portal_keystore_prefs"
        private const val KEY_PROFILE_IDS = "portal_profile_ids"

        private fun getProfileDataKey(id: String) = "profile_meta_$id"
        private fun getPasswordKey(id: String) = "profile_pwd_$id"
        private fun getSsidIndexKey(ssid: String) = "idx_ssid_${ssid.trim().lowercase()}"

        @Volatile
        private var instance: PortalProfileRepository? = null

        fun getInstance(context: Context): PortalProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: PortalProfileRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
