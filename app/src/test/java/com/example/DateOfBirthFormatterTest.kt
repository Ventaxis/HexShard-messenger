package com.example

import com.example.util.DateOfBirthFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DateOfBirthFormatterTest {

    @Test
    fun testRaw8DigitsFormatting() {
        val ruFormatted = DateOfBirthFormatter.formatForDisplay("13041998", isRussian = true)
        assertEquals("13 апреля 1998", ruFormatted)

        val enFormatted = DateOfBirthFormatter.formatForDisplay("13041998", isRussian = false)
        assertEquals("13 April 1998", enFormatted)
    }

    @Test
    fun testDottedAndDashedFormats() {
        assertEquals("13 апреля 1998", DateOfBirthFormatter.formatForDisplay("13.04.1998", isRussian = true))
        assertEquals("13 апреля 1998", DateOfBirthFormatter.formatForDisplay("1998-04-13", isRussian = true))
        assertEquals("13 April 1998", DateOfBirthFormatter.formatForDisplay("13/04/1998", isRussian = false))
    }

    @Test
    fun testCanonicalIsoConversion() {
        assertEquals("1998-04-13", DateOfBirthFormatter.toCanonicalIso("13.04.1998"))
        assertEquals("1998-04-13", DateOfBirthFormatter.toCanonicalIso("13 апреля 1998"))
        assertEquals("1998-04-13", DateOfBirthFormatter.toCanonicalIso("13 April 1998"))
        assertEquals("1998-04-13", DateOfBirthFormatter.toCanonicalIso("13041998"))
        assertEquals("1998-04-13", DateOfBirthFormatter.toCanonicalIso("1998-04-13"))
    }

    @Test
    fun testStrictDateValidationRules() {
        // 29.02.2024 is a leap year -> valid
        assertTrue(DateOfBirthFormatter.isValid("29.02.2024"))
        assertEquals("2024-02-29", DateOfBirthFormatter.toCanonicalIso("29.02.2024"))

        // 29.02.2023 is not a leap year -> invalid
        assertFalse(DateOfBirthFormatter.isValid("29.02.2023"))
        assertNull(DateOfBirthFormatter.toCanonicalIso("29.02.2023"))

        // 31.04.2024 (April has only 30 days) -> invalid
        assertFalse(DateOfBirthFormatter.isValid("31.04.2024"))
        assertNull(DateOfBirthFormatter.toCanonicalIso("31.04.2024"))

        // 00.01.2020 -> invalid
        assertFalse(DateOfBirthFormatter.isValid("00.01.2020"))
        assertNull(DateOfBirthFormatter.toCanonicalIso("00.01.2020"))

        // 32.01.2020 -> invalid
        assertFalse(DateOfBirthFormatter.isValid("32.01.2020"))
        assertNull(DateOfBirthFormatter.toCanonicalIso("32.01.2020"))
    }

    @Test
    fun testAutoFormatTyping() {
        val result = DateOfBirthFormatter.autoFormatTyping("13041998", isRussian = true)
        assertEquals("13 апреля 1998", result)

        val resultDelimited = DateOfBirthFormatter.autoFormatTyping("13.04.1998", isRussian = true)
        assertEquals("13 апреля 1998", resultDelimited)

        val resultSpaced = DateOfBirthFormatter.autoFormatTyping("13 04 1998", isRussian = true)
        assertEquals("13 апреля 1998", resultSpaced)

        val resultPartial = DateOfBirthFormatter.autoFormatTyping("1304", isRussian = true)
        assertEquals("13 апреля", resultPartial)
    }

    @Test
    fun testParseToMillisAndBack() {
        val millis = DateOfBirthFormatter.parseToMillis("13.04.1998")
        assertNotNull(millis)
        val formatted = DateOfBirthFormatter.formatFromMillis(millis!!, isRussian = true)
        assertEquals("13 апреля 1998", formatted)
    }
}
