package me.rerere.rikkahub.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound credentials. An unavailable key fails closed; it never writes plaintext. */
object DeviceSecretCipher {
    private const val PREFIX = "pocket-keystore-v1:"
    private const val ALIAS = "pocket_credentials_v1"
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(PREFIX.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    /** Legacy plaintext is accepted only for migration. Existing ciphertext must authenticate. */
    fun decrypt(value: String): String {
        if (!isEncrypted(value)) return value
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size >= 28) { "Invalid stored credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(PREFIX.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}
