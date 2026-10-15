package com.example.aiaccounting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiaccounting.data.security.EncryptedApiKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {
    @Test fun keyIsEncryptedPersistsAndCanBeRemovedWithoutTouchingUserSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test_credentials_${UUID.randomUUID()}"
        val store = EncryptedApiKeyStore(context, name)
        val value = "local-test-credential"
        try {
            assertNull(store.read())
            store.write(value)
            assertEquals(value, EncryptedApiKeyStore(context, name).read())
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            assertEquals(setOf("iv", "ciphertext"), preferences.keys)
            assertTrue(preferences.values.none { it.toString().contains(value) })
            val previous = preferences["ciphertext"]
            store.write(value)
            assertNotEquals(previous, context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("ciphertext", null))
            store.clear()
            assertNull(store.read())
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("ai_accounting.$name.aes")
        }
    }
}
