package com.example.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shared Supabase HTTP plumbing: one pooled REST client plus the canonical
 * apikey / Authorization / Content-Type header builders used by every
 * PostgREST, Edge Function and Storage call in the app.
 *
 * Files with deliberately different timeouts (large media uploads, snappy key
 * lookups) keep a file-local client but still route their request building
 * through here so the header trio exists in exactly one place.
 */
object SupabaseHttp {

    /** Wire format for every PostgREST/Edge JSON body. */
    val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()

    /** Shared client: connect 15s / read 20s / write 20s — the common REST shape. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Starts a Supabase request with the apikey header and — when [token] is
     * non-null — the Authorization: Bearer header. Pass null for the few
     * unauthenticated endpoints (auth signup/login, token refresh); pass a
     * blank string to reproduce the legacy "Bearer " header shape verbatim.
     * JSON bodies carry their own Content-Type via [JSON_MEDIA].
     */
    fun request(url: String, token: String?, anonKey: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .apply { if (token != null) header("Authorization", "Bearer $token") }

    /**
     * JSON POST/PATCH with the full canonical header set: apikey, Bearer auth
     * and a body serialized as [JSON_MEDIA] (which also carries the
     * Content-Type header on the wire). [method] is "POST" or "PATCH".
     */
    fun jsonRequest(
        url: String,
        token: String?,
        anonKey: String,
        method: String,
        jsonBody: String
    ): Request.Builder =
        request(url, token, anonKey)
            .method(method, jsonBody.toRequestBody(JSON_MEDIA))
}
