package dev.cloudsync.network

import dev.cloudsync.core.error.RemoteChangedException
import dev.cloudsync.core.model.AccountEndpoint
import dev.cloudsync.core.remote.ContentSource
import dev.cloudsync.core.remote.CredentialsProvider
import dev.cloudsync.core.remote.UploadProgress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavClientTest {
    private val account = AccountEndpoint("acc", "https://cloud.example", "alice", "alice")

    @Test
    fun propfindParsesEtagFileIdAndSkipsTheFolderItself() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            val root = server.url("/remote.php/dav/files/alice/").toString()
            server.enqueue(
                MockResponse.Builder()
                    .code(207)
                    .setHeader("Content-Type", "application/xml")
                    .body(propfind(root))
                    .build(),
            )
            val client = client(server)
            val nodes = client.list(account.copy(serverUrl = server.url("/").toString().trimEnd('/')), "/Notes")
            assertEquals(listOf("/Notes/a.txt"), nodes.map { it.path })
            assertEquals("abc", nodes.single().etag)
            assertEquals("99", nodes.single().fileId)
            val recorded = server.takeRequest()
            assertEquals("PROPFIND", recorded.method)
            assertEquals("1", recorded.headers["Depth"])
            assertTrue(recorded.url.encodedPath.endsWith("/Notes/"))
            assertTrue(recorded.headers["Authorization"]!!.startsWith("Basic "))
            assertTrue(recorded.body!!.utf8().contains("<d:propfind"))
        } finally {
            server.close()
        }
    }

    @Test
    fun uploadSendsIfMatchAndSurfaces412() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(204).setHeader("ETag", "\"new\"").build())
            server.enqueue(MockResponse.Builder().code(412).build())
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val client = client(server)
            val uploaded = client.upload(endpoint, "/Notes/a.txt", source("hello"), 5, "text/plain", "old")
            assertEquals("new", uploaded.etag)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("\"old\"", put.headers["If-Match"])
            assertFailsWith<RemoteChangedException> {
                client.upload(endpoint, "/Notes/a.txt", source("hello"), 5, "text/plain", "stale")
            }
            Unit
        } finally {
            server.close()
        }
    }

    @Test
    fun chunkedUploadUsesNextcloudV2() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            repeat(4) { server.enqueue(MockResponse.Builder().code(201).build()) }
            server.enqueue(MockResponse.Builder().code(201).setHeader("ETag", "\"assembled\"").build())
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val client = WebDavClient(OkHttpClient(), StaticSecret("secret"), chunkSize = 4)
            val result = client.upload(endpoint, "/big.bin", source("abcdefghij"), 10, "application/octet-stream", null)
            assertEquals("assembled", result.etag)
            val methods = (1..5).map { server.takeRequest().method }
            assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE"), methods)
        } finally {
            server.close()
        }
    }

    @Test
    fun resumedChunkedUploadAssemblesWithoutRepeatingChunks() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(201).build())
            server.enqueue(MockResponse.Builder().code(201).setHeader("ETag", "\"assembled\"").build())
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val client = WebDavClient(OkHttpClient(), StaticSecret("secret"), chunkSize = 4)
            val progress = mutableListOf<UploadProgress>()
            val result = client.upload(
                endpoint,
                "/big.bin",
                source("abcdefghij"),
                10,
                "application/octet-stream",
                "base-etag",
                uploadId = "cloudsync-resume",
                resumeFromChunk = 3,
                onProgress = { progress += it },
            )
            assertEquals("assembled", result.etag)
            val requests = (1..2).map { server.takeRequest() }
            assertEquals(listOf("PUT", "MOVE"), requests.map { it.method })
            assertTrue(requests[0].url.encodedPath.contains("/cloudsync-resume/00003"))
            assertTrue(requests[1].url.encodedPath.contains("/cloudsync-resume/.file"))
            assertEquals("10", requests[1].headers["OC-Total-Length"])
            assertEquals("\"base-etag\"", requests[1].headers["If-Match"])
            assertTrue(progress.any { it.uploadId == "cloudsync-resume" && it.nextChunk > 3 })
        } finally {
            server.close()
        }
    }

    @Test
    fun downloadKeepsTheBodyForTheCallerToVerify() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"e1\"").body("payload").build())
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val download = client(server).download(endpoint, "/Notes/a.txt", null)
            assertEquals("payload", download.bytes.decodeToString())
            assertEquals("e1", download.etag)
        } finally {
            server.close()
        }
    }

    @Test
    fun propfindRepeatsItselfWhenNextcloudRedirects() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            val root = server.url("/remote.php/dav/files/alice/").toString()
            server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "/listed").build())
            server.enqueue(
                MockResponse.Builder().code(207).setHeader("Content-Type", "application/xml").body(propfind(root)).build(),
            )
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val nodes = client(server).list(endpoint, "/Notes")
            assertEquals(listOf("/Notes/a.txt"), nodes.map { it.path })
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("PROPFIND", first.method)
            assertEquals("PROPFIND", second.method)
            assertEquals("/listed", second.url.encodedPath)
            assertTrue(second.body!!.utf8().contains("<d:propfind"))
        } finally {
            server.close()
        }
    }

    @Test
    fun uploadRepeatsPutWhenNextcloudRedirects() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/stored").build())
            server.enqueue(MockResponse.Builder().code(204).setHeader("ETag", "\"new\"").setHeader("OC-FileId", "15").build())
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val uploaded = client(server).upload(endpoint, "/Notes/a.txt", source("hello"), 5, "text/plain", null)
            assertEquals("new", uploaded.etag)
            assertEquals("15", uploaded.fileId)
            val requests = (1..2).map { server.takeRequest() }
            assertEquals(listOf("PUT", "PUT"), requests.map { it.method })
            assertEquals("/stored", requests[1].url.encodedPath)
            assertEquals("hello", requests[1].body!!.utf8())
        } finally {
            server.close()
        }
    }

    @Test
    fun parsesANextcloud34FolderListing() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:response>
                <d:href>/nextcloud/remote.php/dav/files/alice/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getlastmodified>Mon, 16 Nov 2026 13:38:02 GMT</d:getlastmodified>
                    <d:getetag>&quot;6afb073a5c588&quot;</d:getetag>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <oc:fileid>259748</oc:fileid>
                    <oc:permissions>RGDNVCK</oc:permissions>
                    <oc:size>65574482</oc:size>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getcontentlength/><d:getcontenttype/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/nextcloud/remote.php/dav/files/alice/Nextcloud%20intro.mp4</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>3963036</d:getcontentlength>
                    <d:getcontenttype>video/mp4</d:getcontenttype>
                    <d:getetag>&quot;abc&quot;</d:getetag>
                    <d:resourcetype/>
                    <oc:fileid>259760</oc:fileid>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/nextcloud/remote.php/dav/files/alice/a+b.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>1</d:getcontentlength>
                    <d:getetag>&quot;plus&quot;</d:getetag>
                    <d:resourcetype/>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val nodes = PropfindParser.parse(xml, "https://cloud.example/nextcloud/remote.php/dav/files/alice/")
        val folder = nodes.first { it.path == "/" }
        assertEquals(true, folder.directory)
        assertEquals(65574482L, folder.size)
        assertEquals("6afb073a5c588", folder.etag)
        assertEquals("259748", folder.fileId)
        val video = nodes.first { it.path == "/Nextcloud intro.mp4" }
        assertEquals(false, video.directory)
        assertEquals(3963036L, video.size)
        assertEquals("video/mp4", video.contentType)
        assertEquals("/a+b.txt", nodes.first { it.etag == "plus" }.path)
    }

    @Test
    fun searchUsesTheFilePathFromTheCurrentNextcloudResponse() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(
                    """
                    {"ocs":{"data":{"entries":[
                      {"title":"Readme.md","resourceUrl":"https://cloud.example/f/259761","icon":"/apps/theming/img/core/filetypes/text.svg","attributes":{"fileId":"259761","path":"/Readme.md"}},
                      {"title":"Documents","resourceUrl":"https://cloud.example/f/259755","icon":"icon-folder","attributes":{"fileId":"259755","path":"/Documents"}},
                      {"title":"legacy","resourceUrl":"https://cloud.example/remote.php/dav/files/alice/Notes/a.txt"}
                    ]}}}
                    """.trimIndent(),
                ).build(),
            )
            val endpoint = account.copy(serverUrl = server.url("/").toString().trimEnd('/'))
            val nodes = client(server).search(endpoint, "Readme")
            assertEquals(listOf("/Readme.md", "/Documents", "/Notes/a.txt"), nodes.map { it.path })
            assertEquals(listOf(false, true, false), nodes.map { it.directory })
            assertEquals("259761", nodes.first().fileId)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertTrue(recorded.url.encodedPath.endsWith("/ocs/v2.php/search/providers/files/search"))
            assertEquals("true", recorded.headers["OCS-APIRequest"])
        } finally {
            server.close()
        }
    }

    private fun client(server: MockWebServer) = WebDavClient(OkHttpClient(), StaticSecret("secret"))

    private fun source(text: String) = ContentSource { text.byteInputStream() }

    private fun propfind(root: String) = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>${root}Notes/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>${root}Notes/a.txt</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>"abc"</d:getetag>
                <d:getcontentlength>5</d:getcontentlength>
                <oc:fileid>99</oc:fileid>
                <d:getcontenttype>text/plain</d:getcontenttype>
                <d:getlastmodified>Thu, 01 Jan 1970 00:00:00 GMT</d:getlastmodified>
              </d:prop>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    private class StaticSecret(private val value: String) : CredentialsProvider {
        override suspend fun secret(accountId: String): String = value
    }
}
