package com.example

import com.example.data.repository.AttachmentRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec (K08): "strict allowed MIME validation". The validator must match the exact
 * type/subtype — prefix matching accepted hostile look-alikes such as "image/png-evil".
 */
class AttachmentMimeTypeTest {

    @Test
    fun `exact whitelisted types are accepted`() {
        assertTrue(AttachmentRepository.isMimeTypeAllowed("image/png"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("image/jpeg"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("application/pdf"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("image/heic"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("image/heif"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("video/mp4"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("text/plain"))
    }

    @Test
    fun `suffix look-alikes are rejected`() {
        // Each of these passed the old startsWith() check.
        assertFalse(AttachmentRepository.isMimeTypeAllowed("image/png-evil"))
        assertFalse(AttachmentRepository.isMimeTypeAllowed("image/pngx"))
        assertFalse(AttachmentRepository.isMimeTypeAllowed("text/plaintext"))
        assertFalse(AttachmentRepository.isMimeTypeAllowed("application/pdfx"))
    }

    @Test
    fun `mime parameters are stripped before matching`() {
        assertTrue(AttachmentRepository.isMimeTypeAllowed("text/plain; charset=utf-8"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("image/png; filename=x.png"))
    }

    @Test
    fun `case and surrounding whitespace are normalized`() {
        assertTrue(AttachmentRepository.isMimeTypeAllowed("IMAGE/PNG"))
        assertTrue(AttachmentRepository.isMimeTypeAllowed("  image/png  "))
    }

    @Test
    fun `non-whitelisted and malformed values are rejected`() {
        assertFalse(AttachmentRepository.isMimeTypeAllowed("application/zip"))
        assertFalse(AttachmentRepository.isMimeTypeAllowed(""))
        assertFalse(AttachmentRepository.isMimeTypeAllowed("not-a-mime"))
        assertFalse(AttachmentRepository.isMimeTypeAllowed("image/"))
    }
}
