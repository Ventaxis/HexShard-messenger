package com.example

import com.example.data.repository.BackgroundValidationResult
import com.example.data.repository.ProfileBackgroundManager
import com.example.data.repository.ValidationErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBackgroundValidationTest {

    @Test
    fun testEmptyBytesRejected() {
        val empty = ByteArray(0)
        val result = ProfileBackgroundManager.validateBytes(empty)
        assertTrue(result is BackgroundValidationResult.Error)
        assertEquals(ValidationErrorReason.FILE_EMPTY, (result as BackgroundValidationResult.Error).reason)
    }

    @Test
    fun testFileOver2MiBRejected() {
        // 2 MiB = 2097152 bytes; 2097153 bytes must be strictly rejected
        val oversized = ByteArray((ProfileBackgroundManager.MAX_FILE_SIZE_BYTES + 1).toInt())
        val result = ProfileBackgroundManager.validateBytes(oversized)
        assertTrue(result is BackgroundValidationResult.Error)
        assertEquals(ValidationErrorReason.FILE_TOO_LARGE, (result as BackgroundValidationResult.Error).reason)
    }

    @Test
    fun testFileExactly2MiBAcceptedIfFormatValid() {
        val exact2MiB = ByteArray(ProfileBackgroundManager.MAX_FILE_SIZE_BYTES.toInt())
        // JPEG magic bytes: FF D8 FF
        exact2MiB[0] = 0xFF.toByte()
        exact2MiB[1] = 0xD8.toByte()
        exact2MiB[2] = 0xFF.toByte()

        val result = ProfileBackgroundManager.validateBytes(exact2MiB)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("image/jpeg", valid.mimeType)
        assertEquals("jpg", valid.extension)
        assertFalse(valid.isVideo)
        assertEquals(ProfileBackgroundManager.MAX_FILE_SIZE_BYTES, valid.size)
    }

    @Test
    fun testPngFormatDetected() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x0D
        )
        val result = ProfileBackgroundManager.validateBytes(pngBytes)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("image/png", valid.mimeType)
        assertEquals("png", valid.extension)
        assertFalse(valid.isVideo)
    }

    @Test
    fun testWebpFormatDetected() {
        val webpBytes = ByteArray(16)
        // RIFF
        webpBytes[0] = 'R'.code.toByte()
        webpBytes[1] = 'I'.code.toByte()
        webpBytes[2] = 'F'.code.toByte()
        webpBytes[3] = 'F'.code.toByte()
        // WEBP at offset 8
        webpBytes[8] = 'W'.code.toByte()
        webpBytes[9] = 'E'.code.toByte()
        webpBytes[10] = 'B'.code.toByte()
        webpBytes[11] = 'P'.code.toByte()

        val result = ProfileBackgroundManager.validateBytes(webpBytes)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("image/webp", valid.mimeType)
        assertEquals("webp", valid.extension)
        assertFalse(valid.isVideo)
    }

    @Test
    fun testGifFormatDetected() {
        val gifBytes = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x01, 0x00
        )
        val result = ProfileBackgroundManager.validateBytes(gifBytes)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("image/gif", valid.mimeType)
        assertEquals("gif", valid.extension)
        assertFalse(valid.isVideo)
    }

    @Test
    fun testWebmFormatDetectedAndMarkedAsVideo() {
        // EBML header: 1A 45 DF A3
        val webmBytes = byteArrayOf(
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(),
            0x9F.toByte(), 0x42.toByte(), 0x86.toByte(), 0x81.toByte()
        )
        val result = ProfileBackgroundManager.validateBytes(webmBytes)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("video/webm", valid.mimeType)
        assertEquals("webm", valid.extension)
        assertTrue(valid.isVideo)
    }

    @Test
    fun testMp4FormatDetectedAndMarkedAsVideo() {
        // MP4 ftyp header
        val mp4Bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
        )
        val result = ProfileBackgroundManager.validateBytes(mp4Bytes)
        assertTrue(result is BackgroundValidationResult.Valid)
        val valid = result as BackgroundValidationResult.Valid
        assertEquals("video/mp4", valid.mimeType)
        assertEquals("mp4", valid.extension)
        assertTrue(valid.isVideo)
    }

    @Test
    fun testUnsupportedFormatRejected() {
        val randomExe = byteArrayOf(
            0x4D.toByte(), 0x5A.toByte(), 0x90.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        val result = ProfileBackgroundManager.validateBytes(randomExe)
        assertTrue(result is BackgroundValidationResult.Error)
        assertEquals(ValidationErrorReason.UNSUPPORTED_FORMAT, (result as BackgroundValidationResult.Error).reason)
    }

    @Test
    fun testPublicUrlGeneration() {
        val path = "usr-123/bg_987654.webm"
        val url = ProfileBackgroundManager.getPublicUrl(path)
        assertTrue(url.contains("/storage/v1/object/public/profile-backgrounds/usr-123/bg_987654.webm"))
    }
}
