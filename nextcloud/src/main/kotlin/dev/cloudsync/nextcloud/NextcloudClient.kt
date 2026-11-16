package dev.cloudsync.nextcloud

import dev.cloudsync.core.error.AuthenticationException
import dev.cloudsync.core.error.CloudSyncException
import dev.cloudsync.core.error.NetworkException
import dev.cloudsync.core.error.ValidationException
import dev.cloudsync.core.url.ServerUrls
import dev.cloudsync.network.HttpClients
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class LoginChallenge(val token: String, val endpoint: String, val loginUrl: String)

sealed interface LoginPoll {
    data object Pending : LoginPoll

    data class Success(val server: String, val loginName: String, val appPassword: String) : LoginPoll
}

data class ServerInfo(val serverUrl: String, val version: String?, val product: String?)

data class CloudUser(val userId: String, val displayName: String)

class LoginFlowClient(private val http: OkHttpClient, private val requireHttps: Boolean = true) {
    fun start(serverUrl: String): LoginChallenge {
        val server = canonical(serverUrl)
        val request = Request.Builder()
            .url("$server/index.php/login/v2")
            .header("User-Agent", HttpClients.USER_AGENT)
            .post(FormBody.Builder().build())
            .build()
        val body = call(request)
        val json = Json.parseToJsonElement(body).jsonObject
        val poll = json["poll"]?.jsonObject ?: throw CloudSyncException("The server did not start login flow v2.")
        return LoginChallenge(
            token = poll.string("token"),
            endpoint = poll.string("endpoint"),
            loginUrl = json.string("login"),
        )
    }

    fun poll(endpoint: String, token: String): LoginPoll {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", HttpClients.USER_AGENT)
            .post(FormBody.Builder().add("token", token).build())
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw NetworkException("Could not reach the Nextcloud server.", error)
        }
        response.use {
            if (it.code == 404) return LoginPoll.Pending
            if (!it.isSuccessful) {
                throw CloudSyncException("Login polling failed (${it.code}).")
            }
            val json = Json.parseToJsonElement(it.body.string()).jsonObject
            return LoginPoll.Success(
                server = canonical(json.string("server")),
                loginName = json.string("loginName"),
                appPassword = json.string("appPassword"),
            )
        }
    }

    private fun canonical(serverUrl: String): String = if (requireHttps) ServerUrls.normalize(serverUrl) else serverUrl.trimEnd('/')

    private fun call(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw NetworkException("Could not reach the Nextcloud server.", error)
        }
        response.use {
            if (!it.isSuccessful) {
                throw CloudSyncException("Nextcloud did not accept the login request (${it.code}).")
            }
            return it.body.string()
        }
    }
}

class OcsClient(private val http: OkHttpClient, private val requireHttps: Boolean = true) {
    fun probe(serverUrl: String): ServerInfo {
        val server = canonical(serverUrl)
        val request = Request.Builder()
            .url("$server/ocs/v2.php/cloud/capabilities")
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        val body = execute(request, "That address did not respond like a Nextcloud server.")
        val version = runCatching {
            Json.parseToJsonElement(body).jsonObject["ocs"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("version")?.jsonObject
                ?.string("string")
        }.getOrNull()
        if (!body.contains("\"ocs\"")) {
            throw ValidationException("That server did not return a Nextcloud capabilities document.")
        }
        return ServerInfo(server, version, "nextcloud")
    }

    fun currentUser(serverUrl: String, loginName: String, secret: String): CloudUser {
        val server = canonical(serverUrl)
        val request = authorized(server, loginName, secret, "/ocs/v2.php/cloud/user").get().build()
        val body = execute(request, "Could not read the Nextcloud profile.")
        val data = Json.parseToJsonElement(body).jsonObject["ocs"]?.jsonObject?.get("data")?.jsonObject
            ?: throw CloudSyncException("Nextcloud did not return a user profile.")
        return CloudUser(
            userId = data.string("id"),
            displayName = data["displayname"]?.jsonPrimitive?.contentOrNull ?: loginName,
        )
    }

    /**
     * Turns a normal account password into an app password when the server allows it.
     * An existing app password returns 403 and is kept as-is. The typed value is never logged.
     */
    fun preferAppPassword(serverUrl: String, loginName: String, secret: String): String {
        val server = canonical(serverUrl)
        val request = authorized(server, loginName, secret, "/ocs/v2.php/core/getapppassword").get().build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw NetworkException("Could not reach the Nextcloud server.", error)
        }
        response.use {
            when (it.code) {
                200 -> {
                    val body = it.body.string()
                    val jsonPassword = runCatching {
                        Json.parseToJsonElement(body).jsonObject["ocs"]?.jsonObject
                            ?.get("data")?.jsonObject?.string("apppassword")
                    }.getOrNull()
                    if (jsonPassword != null) return jsonPassword
                    val match = Regex("<apppassword>([^<]+)</apppassword>").find(body)?.groupValues?.get(1)
                    return match ?: secret
                }
                403 -> return secret
                401 -> throw AuthenticationException("Nextcloud rejected that login.")
                else -> throw CloudSyncException("Could not create an app password (${it.code}).")
            }
        }
    }

    fun deleteAppPassword(serverUrl: String, loginName: String, secret: String) {
        val server = canonical(serverUrl)
        val request = authorized(server, loginName, secret, "/ocs/v2.php/core/apppassword").delete().build()
        try {
            http.newCall(request).execute().close()
        } catch (_: IOException) {
            // Disconnect still removes the local credential when the server cannot be reached.
        }
    }

    private fun canonical(serverUrl: String): String = if (requireHttps) ServerUrls.normalize(serverUrl) else serverUrl.trimEnd('/')

    private fun authorized(server: String, loginName: String, secret: String, path: String): okhttp3.Request.Builder = Request.Builder()
        .url(server.trimEnd('/') + path)
        .header("Authorization", Credentials.basic(loginName, secret, Charsets.UTF_8))
        .header("OCS-APIRequest", "true")
        .header("Accept", "application/json")
        .header("User-Agent", HttpClients.USER_AGENT)

    private fun execute(request: Request, failure: String): String {
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw NetworkException("Could not reach the Nextcloud server.", error)
        }
        response.use {
            if (it.code == 401) throw AuthenticationException("Nextcloud rejected the saved login. Sign in again.")
            if (!it.isSuccessful) throw CloudSyncException("$failure (${it.code})")
            return it.body.string()
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: throw CloudSyncException("The server response did not include $name.")
