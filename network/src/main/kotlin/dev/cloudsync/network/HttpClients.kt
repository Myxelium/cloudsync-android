package dev.cloudsync.network

import okhttp3.OkHttpClient
import java.time.Duration

object HttpClients {
    const val USER_AGENT = "CloudSync/1.0 (Android)"

    /**
     * Platform TLS only. There is no custom trust manager and hostname checks stay enabled.
     */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofMinutes(10))
        .readTimeout(Duration.ofMinutes(10))
        .writeTimeout(Duration.ofMinutes(10))
        .retryOnConnectionFailure(true)
        .build()
}
