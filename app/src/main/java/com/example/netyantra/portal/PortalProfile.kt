package com.example.netyantra.portal

import org.json.JSONObject
import java.util.UUID

/**
 * Captive portal authentication profile containing credentials and form submission parameters.
 *
 * @property id Unique identifier for the profile.
 * @property ssid Wi-Fi SSID associated with this portal.
 * @property loginUrl Target HTTP/HTTPS URL to submit the login POST request to.
 * @property usernameField Form parameter name for username (e.g. "username", "user", "email").
 * @property passwordField Form parameter name for password (e.g. "password", "pass").
 * @property extraFields Optional extra key-value pairs (e.g. hidden inputs like "magic", "terms").
 * @property username User credential username.
 * @property password User credential password (never logged, stored in EncryptedSharedPreferences).
 */
data class PortalProfile(
    val id: String = UUID.randomUUID().toString(),
    val ssid: String,
    val loginUrl: String,
    val usernameField: String = "username",
    val passwordField: String = "password",
    val extraFields: Map<String, String> = emptyMap(),
    val username: String,
    val password: String = ""
) {
    fun toJson(includePassword: Boolean = true): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("ssid", ssid)
        json.put("loginUrl", loginUrl)
        json.put("usernameField", usernameField)
        json.put("passwordField", passwordField)
        json.put("username", username)
        if (includePassword) {
            json.put("password", password)
        }
        val extraJson = JSONObject()
        extraFields.forEach { (k, v) ->
            if (k.isNotBlank()) {
                extraJson.put(k, v)
            }
        }
        json.put("extraFields", extraJson)
        return json
    }

    companion object {
        fun fromJson(jsonStr: String, passwordOverride: String? = null): PortalProfile? {
            return try {
                val json = JSONObject(jsonStr)
                val extraJson = json.optJSONObject("extraFields")
                val extras = mutableMapOf<String, String>()
                if (extraJson != null) {
                    val keys = extraJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        extras[key] = extraJson.optString(key, "")
                    }
                }

                PortalProfile(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    ssid = json.optString("ssid", ""),
                    loginUrl = json.optString("loginUrl", ""),
                    usernameField = json.optString("usernameField", "username"),
                    passwordField = json.optString("passwordField", "password"),
                    extraFields = extras,
                    username = json.optString("username", ""),
                    password = passwordOverride ?: json.optString("password", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
