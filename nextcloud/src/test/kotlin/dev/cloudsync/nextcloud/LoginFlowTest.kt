package dev.cloudsync.nextcloud

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginFlowTest {
    @Test
    fun loginFlowV2PollsUntilTheAppPasswordArrives() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"poll":{"token":"tok","endpoint":"$base/login/v2/poll"},"login":"$base/login/v2/flow/abc"}""",
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"server":"$base","loginName":"alice","appPassword":"app-secret"}""",
                ).build(),
            )
            val client = LoginFlowClient(OkHttpClient(), requireHttps = false)
            val challenge = client.start(base)
            assertEquals("tok", challenge.token)
            assertIs<LoginPoll.Pending>(client.poll(challenge.endpoint, challenge.token))
            val success = client.poll(challenge.endpoint, challenge.token) as LoginPoll.Success
            assertEquals("alice", success.loginName)
            assertEquals("app-secret", success.appPassword)
            assertEquals("POST", server.takeRequest().method)
        } finally {
            server.close()
        }
    }

    @Test
    fun existingAppPasswordIsKeptAndARealPasswordIsExchanged() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse.Builder().code(403).build())
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"ocs":{"data":{"apppassword":"generated"}}}""",
                ).build(),
            )
            val client = OcsClient(OkHttpClient(), requireHttps = false)
            assertEquals("already-app", client.preferAppPassword(base, "alice", "already-app"))
            assertEquals("generated", client.preferAppPassword(base, "alice", "real-password"))
        } finally {
            server.close()
        }
    }
}
