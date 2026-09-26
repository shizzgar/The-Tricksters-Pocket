package me.rerere.rikkahub.data.sync

import android.content.Context
import me.rerere.rikkahub.data.security.DeviceSecretCipher

/** One explicit policy for local, WebDAV and S3 exports, including background callers. */
class BackupSecurityStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup_protection", Context.MODE_PRIVATE)
    val includeCredentials get() = preferences.getBoolean("encrypted_full", false)
    fun password(): String = DeviceSecretCipher.decrypt(preferences.getString("password", "").orEmpty())
    fun save(includeCredentials: Boolean, password: String) {
        require(!includeCredentials || password.length >= 8) { "Use at least 8 characters for encrypted backup" }
        val encrypted = DeviceSecretCipher.encrypt(password)
        check(preferences.edit().putBoolean("encrypted_full", includeCredentials).putString("password", encrypted).commit())
    }
}
