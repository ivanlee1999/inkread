package me.ash.reader.infrastructure.readwise

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext only, under app-private noBackupFilesDir. The key never leaves Keystore. */
internal class KeystoreReadwiseCredentialStore(context: Context, private val keyAlias: String = DEFAULT_KEY_ALIAS) : ReadwiseCredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val keyStore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized override fun read(): ReadwiseCredential? {
        if (!file.baseFile.exists()) return null
        require(file.baseFile.length() <= MAX_FILE_BYTES) { "Invalid credential record" }
        val bytes = file.readFully()
        require(bytes.size in 32..MAX_FILE_BYTES) { "Invalid credential record" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == VERSION) { "Unsupported credential record" }
        val iv = ByteArray(12).also(input::readFully)
        val encrypted = ByteArray(input.available()).also(input::readFully)
        val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: error("Credential key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(encrypted)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { data ->
                val connectionId = data.readUTF()
                val token = data.readUTF()
                require(data.available() == 0 && connectionId.isNotBlank() && token.isNotBlank())
                return ReadwiseCredential(connectionId, token)
            }
        } finally { plain.fill(0) }
    }

    @Synchronized override fun write(credential: ReadwiseCredential) {
        require(credential.token.length in 1..4096)
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.writeUTF(credential.connectionId); it.writeUTF(credential.token) }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val output = file.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(VERSION)
            data.write(cipher.iv)
            data.write(encrypted)
            data.flush()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    @Synchronized override fun clear() {
        file.delete()
        keyStore.deleteEntry(keyAlias)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    companion object {
        private const val FILE_NAME = "readwise-credential.v1"
        fun hasStoredCredential(context: Context): Boolean = File(context.noBackupFilesDir, FILE_NAME).exists()
        private const val DEFAULT_KEY_ALIAS = "inkread.readwise.token.v1"
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 32 * 1024
        private val AAD = "inkread.readwise.credential.v1".toByteArray(Charsets.UTF_8)
    }
}
