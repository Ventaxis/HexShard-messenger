package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import timber.log.Timber

/**
 * Authoritative resolved profile returned by Supabase RPC (resolve_profile_share_token).
 * Never contains plaintext sensitive credentials, private keys, or session tokens.
 */
data class ResolvedProfile(
    val userId: String,
    val username: String,
    val avatarUrl: String = "",
    val profileBackgroundPath: String = "",
    val profileBackgroundType: String = "",
    val hexNumber: String = "",
    val publicKey: String = "",
    val keyVersion: Int = 1
) {
    val displayName: String get() = username.ifBlank { hexNumber.ifBlank { userId } }
}

// Legacy alias for compatibility during migration
typealias ProfileQrData = ResolvedProfile

object QrCodeManager {

    const val SCHEME = "hexshard"
    const val HOST = "profile"
    const val VERSION_V2 = "v2"

    private val TOKEN_REGEX = Regex("^[a-zA-Z0-9_-]{32,2048}$")

    /**
     * Builds v2 tokenized profile URI: hexshard://profile/v2/<random-token>
     * Contains NO userId, NO public keys, and NO plaintext metadata.
     */
    fun buildProfileUri(token: String): String {
        require(token.isNotBlank()) { "Token must not be blank" }
        return "$SCHEME://$HOST/$VERSION_V2/$token"
    }

    /**
     * Strictly parses and validates v2 tokenized profile URI.
     * Rejects invalid schemes, authorities, non-v2 paths, or malformed tokens.
     * Never logs sensitive token in Timber.
     */
    fun parseProfileToken(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(rawText.trim())
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.authority, ignoreCase = true)) {
                Timber.w("Rejected QR: invalid scheme or host")
                return null
            }
            val segments = uri.pathSegments
            if (segments.size < 2 || segments[0] != VERSION_V2) {
                Timber.w("Rejected QR: unsupported version or path structure")
                return null
            }
            val token = segments[1].trim()
            if (!TOKEN_REGEX.matches(token)) {
                Timber.w("Rejected QR: token failed validation constraints")
                return null
            }
            token
        } catch (e: Exception) {
            Timber.e("Exception during QR token parsing")
            null
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Error generating QR bitmap")
            null
        }
    }

    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            Timber.d("QR decoding failed or no barcode found: ${e.message}")
            null
        }
    }
}
