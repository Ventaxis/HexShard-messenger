package com.example.data

import android.content.Context
import timber.log.Timber
import java.util.Base64
import java.security.MessageDigest

/**
 * Trust-On-First-Use pinning store for peer identity keys.
 *
 * The Supabase server is the distribution channel for peer public keys, so without
 * local pinning a compromised server can silently substitute ECDH/ECDSA keys and
 * impersonate any peer. This store remembers the first key ever seen for a peer
 * (per current account) and reports any later change, letting the crypto layer
 * fall back to the pinned key for verification and surface the change instead of
 * silently trusting the replacement.
 *
 * Stored as raw Base64 of key encoding + a change flag, inside the encrypted
 * shared preferences, namespaced per account: keys never mix across accounts.
 */
object PeerKeyTrustStore {

    private const val PREF_ECDH = "tofu_ecdh_"
    private const val PREF_SIGN = "tofu_sign_"
    private const val PREF_CHANGED = "tofu_key_changed_"

    fun pinEcdhKey(context: Context, accountId: String, peerId: String, encodedKey: ByteArray): Boolean {
        return pin(context, accountId, peerId, PREF_ECDH, encodedKey)
    }

    fun pinSigningKey(context: Context, accountId: String, peerId: String, encodedKey: ByteArray): Boolean {
        return pin(context, accountId, peerId, PREF_SIGN, encodedKey)
    }

    fun getPinnedEcdhKey(context: Context, accountId: String, peerId: String): ByteArray? {
        return get(context, accountId, peerId, PREF_ECDH)
    }

    fun getPinnedSigningKey(context: Context, accountId: String, peerId: String): ByteArray? {
        return get(context, accountId, peerId, PREF_SIGN)
    }

    /** True exactly once after the peer's key changed vs the pinned one; acks the change. */
    fun consumeKeyChangedFlag(context: Context, accountId: String, peerId: String): Boolean {
        val prefs = SecurePrefsManager.getPrefs(context)
        val key = PREF_CHANGED + accountId + "_" + peerId
        val changed = prefs.getBoolean(key, false)
        if (changed) prefs.edit().putBoolean(key, false).apply()
        return changed
    }

    fun clearForAccount(context: Context, accountId: String) {
        val prefs = SecurePrefsManager.getPrefs(context)
        val editor = prefs.edit()
        val prefixes = listOf(PREF_ECDH + accountId + "_", PREF_SIGN + accountId + "_", PREF_CHANGED + accountId + "_")
        for (name in prefs.all.keys) {
            if (prefixes.any { name.startsWith(it) }) editor.remove(name)
        }
        editor.apply()
    }

    /** Stable short fingerprint for future QR/safety-number verification surfaces. */
    fun fingerprint(encodedKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encodedKey)
        return digest.take(8).joinToString(" ") { String.format("%02X", it) }
    }

    private fun pin(context: Context, accountId: String, peerId: String, prefix: String, encodedKey: ByteArray): Boolean {
        if (accountId.isBlank() || peerId.isBlank() || encodedKey.isEmpty()) return false
        val prefs = SecurePrefsManager.getPrefs(context)
        val key = prefix + accountId + "_" + peerId
        val newB64 = Base64.getEncoder().encodeToString(encodedKey)
        val existing = prefs.getString(key, null)
        return if (existing == null) {
            prefs.edit().putString(key, newB64).apply()
            true // first sighting: pinned
        } else if (existing != newB64) {
            // Key rotation or substitution. Keep the ORIGINAL pinned key for verification;
            // the caller decides when to explicitly re-pin. Record the change event.
            prefs.edit().putBoolean(PREF_CHANGED + accountId + "_" + peerId, true).apply()
            Timber.w("TOFU: identity key changed for peer $peerId (account $accountId)")
            false
        } else {
            true // matches pin
        }
    }

    private fun get(context: Context, accountId: String, peerId: String, prefix: String): ByteArray? {
        if (accountId.isBlank() || peerId.isBlank()) return null
        val b64 = SecurePrefsManager.getPrefs(context).getString(prefix + accountId + "_" + peerId, null) ?: return null
        return try {
            Base64.getDecoder().decode(b64)
        } catch (_: Exception) {
            null
        }
    }
}
