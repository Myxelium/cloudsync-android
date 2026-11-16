package dev.cloudsync.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    @Test
    fun keystoreRoundTripAndDelete() = runBlocking {
        val store = KeystoreCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val account = "account-keystore"
        store.delete(account)
        store.put(account, "generated-app-password")
        assertEquals("generated-app-password", store.secret(account))
        store.delete(account)
        assertNull(store.secret(account))
    }
}
