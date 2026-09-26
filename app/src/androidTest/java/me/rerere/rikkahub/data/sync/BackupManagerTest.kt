package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("backup-test-${directory.name}-$name", mode)
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
    }

    @After fun tearDown() {
        liveDatabase.close()
        directory.deleteRecursively()
    }

    @Test fun oldArchiveIgnoresShmAndLeavesLiveDatabaseUntouchedUntilStartup() = runBlocking {
        val source = File(directory, "source")
        val archive = File(directory, "legacy.zip")
        withRoom(source) { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            DatabaseBackup.checkpoint(db)
            db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
                addFile(zip, DatabaseBackup.WAL, File(source.path + "-wal"))
                zip.putNextEntry(ZipEntry(DatabaseBackup.SHM))
                zip.write("deliberately invalid SHM".toByteArray())
                zip.closeEntry()
            }
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        liveDatabase.close()
        BackupManager.applyPendingRestore(context, JsonInstant)
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("archived", probe(liveDatabase))
        ZipFile(archive).use { assertTrue(it.getEntry(DatabaseBackup.SHM) != null) }
    }

    @Test fun newArchiveContainsStandaloneDatabaseAndNoWalOrShm() = runBlocking {
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun unsupportedSchemaIsRejectedBeforePublishing() = runBlocking {
        val future = File(directory, "future")
        withRoom(future) {
            it.openHelper.writableDatabase.version = 9999
        }
        val archive = File(directory, "future.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, future) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun defaultArchiveRemovesSshSecretsIncludingDeletedSQLiteBytes() = runBlocking {
        liveDatabase.openHelper.writableDatabase.execSQL(
            "INSERT INTO ssh_hosts (name,host,port,user,password,privateKey,passphrase,createdAtMs) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any>("synthetic-host", "localhost", 22, "tester", "synthetic-backup-password", "synthetic-private-key", "synthetic-passphrase", 0))
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false, includeCredentials = false, password = "")
        ZipFile(archive).use { zip ->
            val bytes = zip.getInputStream(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE)).use { it.readBytes() }
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertFalse(text.contains("synthetic-backup-password"))
            assertFalse(text.contains("synthetic-private-key"))
            assertFalse(text.contains("synthetic-passphrase"))
            assertEquals(null, zip.getEntry("skill-secrets.json"))
        }
        // The live database is never rewritten by credential stripping.
        liveDatabase.openHelper.readableDatabase.query("SELECT password FROM ssh_hosts WHERE name='synthetic-host'").use {
            assertTrue(it.moveToFirst()); assertEquals("synthetic-backup-password", it.getString(0))
        }
    }

    @Test fun encryptedBackupAuthenticatesBeforePublishingAndProtectsRestoredCredentials() = runBlocking {
        liveDatabase.openHelper.writableDatabase.execSQL(
            "INSERT INTO ssh_hosts (name,host,port,user,password,createdAtMs) VALUES (?,?,?,?,?,?)",
            arrayOf<Any>("synthetic-host", "localhost", 22, "tester", "synthetic-portable-secret", 0))
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false,
            includeCredentials = true, password = "synthetic-archive-password")
        ZipFile(archive).use { zip ->
            assertEquals(1, zip.size())
            assertTrue(zip.getEntry(PortableBackupCipher.ENTRY) != null)
            assertEquals(null, zip.getEntry("settings.json"))
        }
        var rejected = false
        try { manager.stageRestore(archive, true, false, "incorrect-password") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
        manager.stageRestore(archive, true, false, "synthetic-archive-password")
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        withRoom(staged) { room ->
            room.openHelper.readableDatabase.query("SELECT password FROM ssh_hosts WHERE name='synthetic-host'").use {
                assertTrue(it.moveToFirst())
                val saved = it.getString(0)
                assertTrue(me.rerere.rikkahub.data.security.DeviceSecretCipher.isEncrypted(saved))
                assertEquals("synthetic-portable-secret", me.rerere.rikkahub.data.security.DeviceSecretCipher.decrypt(saved))
            }
        }
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending/authenticated.zip").exists())
    }

    @Test fun malformedProjectMetadataIsRejectedBeforePublishing() = runBlocking {
        val archive = File(directory, "invalid-project.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("projects.json"))
            zip.write("""[{"id":"invalid-id","name":"synthetic"}]""".toByteArray())
            zip.closeEntry()
        }
        assertTrue(runCatching { manager.stageRestore(archive, false, false, "") }.isFailure)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun <T> withRoom(file: File, block: (AppDatabase) -> T): T {
        val room = AppDatabaseFactory.create(context, file.path)
        return try {
            block(room)
        } finally {
            room.close()
        }
    }
}
