package com.example.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object CertificatePinnerManager {
    /**
     * Creates a hardened OkHttpClient using Android system trust anchors
     * (configured via network_security_config.xml with cleartextTrafficPermitted=false).
     */
    fun createSecureOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "HexShard/1.0 (Android)")
                    .addHeader("X-Client-Version", "Android/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
