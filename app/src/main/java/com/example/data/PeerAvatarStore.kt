package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Process-wide cache of peer avatar URLs, keyed by peer uid, persisted to a
 * small JSON file so avatars survive restarts. UI reads [flow] and falls back
 * to initials when a peer has no URL yet.
 */
object PeerAvatarStore {
    private val _urls = MutableStateFlow<Map<String, String>>(emptyMap())
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    private var appContext: Context? = null
    private val file: java.io.File?
        get() = appContext?.let { java.io.File(it.filesDir, "peer_avatars.json") }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        try {
            val f = file ?: return
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotBlank()) map[k] = v
            }
            _urls.value = map
        } catch (e: Exception) {
            timber.log.Timber.w(e, "PeerAvatarStore load failed")
        }
    }

    fun get(peerId: String): String? = _urls.value[peerId]

    fun put(context: Context, peerId: String, url: String) {
        if (peerId.isBlank() || url.isBlank()) return
        appContext = appContext ?: context.applicationContext
        val next = _urls.value.toMutableMap()
        next[peerId] = url
        _urls.value = next
        try {
            val f = file ?: return
            f.writeText(JSONObject(next).toString())
        } catch (e: Exception) {
            Timber.w(e, "PeerAvatarStore persist failed")
        }
    }
}
