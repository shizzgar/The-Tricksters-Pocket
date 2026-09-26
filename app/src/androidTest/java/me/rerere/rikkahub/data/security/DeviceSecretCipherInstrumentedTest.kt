package me.rerere.rikkahub.data.security

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.migration.CredentialPreferencesMigration
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class DeviceSecretCipherInstrumentedTest {
    @Test fun preferencesMigrationEncryptsLegacyValuesAtomicallyAndRemainsReadable() = runBlocking {
        val key = stringPreferencesKey("providers")
        val data = mutablePreferencesOf(key to "[{\"apiKey\":\"synthetic-migration-key\"}]")
        val migration = CredentialPreferencesMigration()
        assertTrue(migration.shouldMigrate(data))
        val migrated = migration.migrate(data)
        assertTrue(DeviceSecretCipher.isEncrypted(migrated[key]!!))
        assertFalse(migrated[key]!!.contains("synthetic-migration-key"))
        assertEquals(data[key], DeviceSecretCipher.decrypt(migrated[key]!!))
        assertFalse(migration.shouldMigrate(migrated))
        assertFalse(DeviceSecretCipher.isEncrypted(data[key]!!))
    }

    @Test fun ciphertextTamperingFailsClosed() {
        val encrypted = DeviceSecretCipher.encrypt("synthetic-device-key")
        val prefix = encrypted.substringBefore(':') + ":"
        val bytes = java.util.Base64.getDecoder().decode(encrypted.substringAfter(':'))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val corrupted = prefix + java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { DeviceSecretCipher.decrypt(corrupted) }.isFailure)
    }

    @Test fun legacySkillFallbackMigratesAndPortableSkillImportUsesNewDeviceEncryption() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "synthetic-secrets-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val preferences = context.getSharedPreferences("skill_secrets", Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("skill_secret_test__token", java.util.Base64.getEncoder().encodeToString("synthetic-skill-key".toByteArray()))
                .putString("skill_iv_test__token", "__no_keystore__").commit()
            val store = SkillSecretsStore(context)
            assertEquals("synthetic-skill-key", store.get("test", "token"))
            assertNotEquals("__no_keystore__", preferences.getString("skill_iv_test__token", null))
            val portable = store.portableSnapshot()
            val prepared = store.preparePortableRestore(portable)
            assertFalse(prepared.contains("synthetic-skill-key"))
            val restore = File(directory, "private_credentials/skill-secrets.json")
            restore.parentFile!!.mkdirs(); restore.writeText(prepared)
            preferences.edit().clear().commit()
            assertEquals("synthetic-skill-key", SkillSecretsStore(context).get("test", "token"))
            assertFalse(restore.exists())
        } finally { preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
}
