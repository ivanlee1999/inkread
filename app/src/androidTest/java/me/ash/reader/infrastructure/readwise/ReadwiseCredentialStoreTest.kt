package me.ash.reader.infrastructure.readwise

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic token, isolated directory and Keystore alias. Never reads the owner's credential. */
@RunWith(AndroidJUnit4::class)
class ReadwiseCredentialStoreTest {
    @Test fun ciphertextSurvivesRecreationWithoutContainingPlaintextAndDisconnectDeletesIt() {
        isolated { context, alias ->
            val token = "synthetic-fixture-not-an-account-token"
            assertFalse(ReadwiseConnectionManager.hasStoredConnection(context))
            val first = KeystoreReadwiseCredentialStore(context, alias)
            first.write(ReadwiseCredential("synthetic-connection", token))
            assertTrue(ReadwiseConnectionManager.hasStoredConnection(context))
            val ciphertext = File(context.noBackupFilesDir, "readwise-credential.v1").readBytes()
            assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains(token))
            val restored = KeystoreReadwiseCredentialStore(context, alias).read()!!
            assertEquals("synthetic-connection", restored.connectionId)
            assertEquals(token, restored.token)
            first.clear()
            assertNull(first.read())
            assertFalse(ReadwiseConnectionManager.hasStoredConnection(context))
            assertFalse(File(context.noBackupFilesDir, "readwise-credential.v1").exists())
        }
    }

    @Test fun tamperingFailsClosedRatherThanReturningAnyToken() {
        isolated { context, alias ->
            val store = KeystoreReadwiseCredentialStore(context, alias)
            store.write(ReadwiseCredential("connection", "synthetic-fixture"))
            val file = File(context.noBackupFilesDir, "readwise-credential.v1")
            file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            try { store.read(); fail("Modified AES-GCM ciphertext must not decrypt") }
            catch (_: java.security.GeneralSecurityException) { }
        }
    }

    private fun isolated(test: (ContextWrapper, String) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val folder = File(app.noBackupFilesDir, "readwise-test-$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir(): File = folder }
        val alias = "inkread.readwise.test.$id"
        try { test(context, alias) }
        finally { KeystoreReadwiseCredentialStore(context, alias).clear(); folder.deleteRecursively() }
    }
}
