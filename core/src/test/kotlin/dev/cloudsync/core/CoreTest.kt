package dev.cloudsync.core

import dev.cloudsync.core.error.ValidationException
import dev.cloudsync.core.etag.Etags
import dev.cloudsync.core.hash.ContentHash
import dev.cloudsync.core.log.LogRedactor
import dev.cloudsync.core.path.PathFilter
import dev.cloudsync.core.path.SyncPaths
import dev.cloudsync.core.retry.RetryPolicy
import dev.cloudsync.core.text.TextFiles
import dev.cloudsync.core.url.ServerUrls
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun hashesAreStableSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.sha256(ByteArray(0)),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ContentHash.sha256("hello".encodeToByteArray()),
        )
    }

    @Test
    fun etagsIgnoreWeakPrefixAndQuotes() {
        assertTrue(Etags.same("\"abc\"", "W/\"abc\""))
        assertFalse(Etags.same("abc", "abd"))
        assertEquals("\"abc\"", Etags.headerValue("abc"))
        assertEquals("\"abc\"", Etags.headerValue("\"abc\""))
        assertNull(Etags.normalize("  "))
    }

    @Test
    fun serverUrlsRequireHttpsWithoutCredentials() {
        assertEquals("https://cloud.example.com", ServerUrls.normalize("cloud.example.com"))
        assertEquals("https://cloud.example.com:8443", ServerUrls.normalize("https://cloud.example.com:8443/"))
        assertFailsWith<ValidationException> { ServerUrls.normalize("http://cloud.example.com") }
        assertFailsWith<ValidationException> { ServerUrls.normalize("https://user:secret@cloud.example.com") }
        assertFailsWith<ValidationException> { ServerUrls.normalize("https://localhost") }
    }

    @Test
    fun pathsRejectTraversalAndFilterGlobs() {
        assertEquals("docs/a.txt", SyncPaths.normalizeRelative("/docs/./a.txt"))
        assertEquals("docs", SyncPaths.parent("docs/a.txt"))
        assertEquals("a.txt", SyncPaths.stripPrefix("/docs/a.txt", "/docs"))
        assertFailsWith<dev.cloudsync.core.error.InvalidNameException> {
            SyncPaths.normalizeRelative("docs/../secret")
        }
        assertTrue(PathFilter.ignored("notes.tmp", true, listOf("*.tmp")))
        assertTrue(PathFilter.ignored("nested/file.txt", includeSubfolders = false, userPatterns = emptyList()))
        assertTrue(PathFilter.ignored(".cloudsync/state", true, emptyList()))
        assertFalse(PathFilter.ignored("notes.txt", true, listOf("*.tmp")))
    }

    @Test
    fun retryBackoffStopsOnPermanentErrors() {
        assertEquals(2_000, RetryPolicy.nextDelayMillis(0))
        assertEquals(4_000, RetryPolicy.nextDelayMillis(1))
        assertNull(RetryPolicy.nextDelayMillis(8))
        assertTrue(RetryPolicy.shouldRetryStatus(503, 1))
        assertFalse(RetryPolicy.shouldRetryStatus(412, 0))
        assertFalse(RetryPolicy.shouldRetryStatus(401, 0))
    }

    @Test
    fun redactorRemovesSecretsAndRejectsBinaryText() {
        val redacted = LogRedactor.redact("Authorization: Basic abc123 password=hunter2")
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("hunter2"))
        assertNull(TextFiles.decodeUtf8(byteArrayOf(0x68, 0x00, 0x69)))
        assertNull(TextFiles.decodeUtf8(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        assertEquals("ok", TextFiles.decodeUtf8("ok".encodeToByteArray()))
    }
}
