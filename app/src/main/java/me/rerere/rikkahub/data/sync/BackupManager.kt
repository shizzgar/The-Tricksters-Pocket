package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        includeCredentials: Boolean = BackupSecurityStore(context).includeCredentials,
        password: String = if (includeCredentials) BackupSecurityStore(context).password() else "",
    ): File = withContext(Dispatchers.IO) {
        require(!includeCredentials || password.length >= 8) { "Set an encryption password before exporting credentials" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeToJsonElement(settings)
                zip.write((if (includeCredentials) settingsJson else BackupCredentials.strip(settingsJson)).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    DatabaseBackup.protectSshCredentials(context, snapshot, includeCredentials)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeCredentials) {
                    zip.putNextEntry(ZipEntry("skill-secrets.json"))
                    zip.write(SkillSecretsStore(context).portableSnapshot().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                File(context.filesDir, "projects.json").takeIf { it.isFile }?.let { addFile(zip, it, "projects.json") }
                if (includeDatabase) {
                    File(context.filesDir, "task-results").listFiles().orEmpty()
                        .filter { it.isFile && it.extension == "json" }.forEach { addFile(zip, it, "task-results/${it.name}") }
                }
                if (includeFiles) {
                    for (folder in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.IMAGES)) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder == FileFolders.SKILLS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            if (includeCredentials) {
                val plain = File(staging, "plain.zip")
                check(archive.renameTo(plain))
                val chars = password.toCharArray()
                try {
                    ZipOutputStream(FileOutputStream(archive)).use { zip ->
                        zip.putNextEntry(ZipEntry(PortableBackupCipher.ENTRY))
                        plain.inputStream().use { PortableBackupCipher.encrypt(it, zip, chars) }
                        zip.closeEntry()
                    }
                } finally { chars.fill('\u0000'); plain.delete() }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean,
        password: String = BackupSecurityStore(context).password()) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    // Authentication completes before any parsing or publication of the staged restore.
                    var encryptedArchive = false
                    val plain = File(staging, "authenticated.zip")
                    val inputArchive = ZipFile(archive).use { outer ->
                        val encrypted = outer.getEntry(PortableBackupCipher.ENTRY)
                        if (encrypted == null) archive else {
                            require(outer.size() == 1) { "Encrypted backup has unexpected entries" }
                            val chars = password.toCharArray()
                            try {
                                outer.getInputStream(encrypted).use { input -> plain.outputStream().use { output ->
                                    PortableBackupCipher.decrypt(input, output, chars)
                                } }
                            } finally { chars.fill('\u0000') }
                            encryptedArchive = true
                            plain
                        }
                    }
                    var extractedBytes = 0L
                    ZipFile(inputArchive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                "projects.json" -> File(payload, "files/projects.json")
                                "skill-secrets.json" -> if (encryptedArchive) File(staging, "skill-secrets.json") else null
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeDatabase && entry.name.startsWith("task-results/") &&
                                    entry.name.substringAfter('/').matches(Regex("[a-fA-F0-9-]{36}\\.json"))) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        extractedBytes += count
                                        require(extractedBytes <= 4L * 1024 * 1024 * 1024) { "Expanded backup exceeds 4 GiB" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // A backup exported from upstream RikkaHub lacks the fork-only tables and
                        // carries upstream's schema version; reconcile the staged copy before Room
                        // opens it so the import neither fails validation nor crashes on first launch.
                        ImportedDatabaseReconciler.reconcileDatabaseFile(stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                        DatabaseBackup.protectSshCredentials(context, stagedDatabase, includeCredentials = true, forRestore = true)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, me.rerere.rikkahub.data.security.DeviceSecretCipher.encrypt(json.encodeToString(settings)))
                    }
                    File(payload, "files/task-results").listFiles().orEmpty().forEach { metadata ->
                        me.rerere.rikkahub.data.task.TaskArtifactStore.validateBackupDocument(metadata.readText(), metadata.nameWithoutExtension)
                    }
                    val projectsFile = File(payload, "files/projects.json")
                    if (projectsFile.isFile) {
                        val projects = json.decodeFromString<List<me.rerere.rikkahub.data.repository.PocketProject>>(projectsFile.readText())
                        fun validId(value: String) = java.util.UUID.fromString(value).toString() == value
                        require(projects.size <= 10_000 && projects.map { it.id }.distinct().size == projects.size) { "Invalid project metadata" }
                        projects.forEach { project ->
                            require(validId(project.id) && project.name.isNotBlank()) { "Invalid project" }
                            project.workspaceId?.let { require(validId(it)) }
                            project.conversationIds.forEach { require(validId(it)) }
                            project.files.forEach { reference ->
                                require(reference.relativePath.startsWith("upload/")) { "Invalid project attachment" }
                                PendingRestore.resolveInside(File(payload, "files"), reference.relativePath)
                            }
                        }
                    }
                    val skillSecrets = File(staging, "skill-secrets.json")
                    if (skillSecrets.isFile) {
                        val encoded = SkillSecretsStore(context).preparePortableRestore(skillSecrets.readText())
                        val target = File(payload, "files/private_credentials/skill-secrets.json")
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        PendingRestore.writeDurably(target, encoded)
                        skillSecrets.delete()
                    }
                    // Never leave a decrypted portable archive in durable restore state.
                    plain.delete()
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.IMAGES) || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder == FileFolders.SKILLS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(me.rerere.rikkahub.data.security.DeviceSecretCipher.decrypt(settingsJson)))
            }
        }
    }
}
