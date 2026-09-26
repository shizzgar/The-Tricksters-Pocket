package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved SSH host the LLM (or user) can reference by name.
 *
 * Secrets are encrypted by SshHostRepository with a device-bound Android Keystore key.
 * Legacy plaintext rows migrate on access; portable full backups re-encrypt them with
 * the user's archive password.
 */
@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    /** Display name; also the lookup key from the LLM. */
    @PrimaryKey val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val createdAtMs: Long,
)
