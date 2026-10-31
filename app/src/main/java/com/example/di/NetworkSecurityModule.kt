package com.example.di

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object NetworkSecurityModule {

    /**
     * Creates a hardened OkHttpClient for API calls with:
     * - TLS 1.3 / 1.2 negotiation
     * - Strict connect/read/write timeouts
     * - Client identification and security headers
     * - System trust anchors from network_security_config.xml
     */
    fun createSecureHttpClient(context: Context): OkHttpClient {
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

    fun createSecureRetrofit(context: Context, baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(createSecureHttpClient(context))
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
    }
}
