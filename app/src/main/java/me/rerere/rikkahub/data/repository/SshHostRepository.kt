package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.security.DeviceSecretCipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SshHostRepository(private val dao: SshHostDao) {
    private val mutex = Mutex()
    suspend fun getAll(): List<SshHostEntity> = mutex.withLock { dao.getAll().map { decodeAndMigrate(it) } }
    suspend fun getByName(name: String): SshHostEntity? = mutex.withLock { dao.getByName(name)?.let { decodeAndMigrate(it) } }
    suspend fun upsert(host: SshHostEntity) = mutex.withLock { dao.upsert(encode(host)) }
    suspend fun deleteByName(name: String) = mutex.withLock { dao.deleteByName(name) }

    private fun encode(host: SshHostEntity) = host.copy(
        password = host.password?.let(DeviceSecretCipher::encrypt),
        privateKey = host.privateKey?.let(DeviceSecretCipher::encrypt),
        passphrase = host.passphrase?.let(DeviceSecretCipher::encrypt),
    )
    private suspend fun decodeAndMigrate(host: SshHostEntity): SshHostEntity {
        val decoded = host.copy(
            password = host.password?.let(DeviceSecretCipher::decrypt),
            privateKey = host.privateKey?.let(DeviceSecretCipher::decrypt),
            passphrase = host.passphrase?.let(DeviceSecretCipher::decrypt),
        )
        if (listOf(host.password, host.privateKey, host.passphrase).any { !it.isNullOrEmpty() && !DeviceSecretCipher.isEncrypted(it) }) {
            dao.upsert(encode(decoded))
        }
        return decoded
    }
}
