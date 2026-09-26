package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import me.rerere.rikkahub.data.security.DeviceSecretCipher

/** Atomic startup migration; failure keeps the original store and never substitutes defaults. */
class CredentialPreferencesMigration : DataMigration<Preferences> {
    private val keys = listOf("assistants", "providers", "search_services", "search_common", "mcp_servers", "webdav_config",
        "s3_config", "tts_providers", "asr_providers", "network_setting", "web_server_access_password").map(::stringPreferencesKey)
    override suspend fun shouldMigrate(currentData: Preferences) = keys.any {
        currentData[it]?.let { value -> value.isNotEmpty() && !DeviceSecretCipher.isEncrypted(value) } == true
    }
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        keys.forEach { key -> this[key]?.takeUnless(DeviceSecretCipher::isEncrypted)?.let { this[key] = DeviceSecretCipher.encrypt(it) } }
    }.toPreferences()
    override suspend fun cleanUp() = Unit
}
