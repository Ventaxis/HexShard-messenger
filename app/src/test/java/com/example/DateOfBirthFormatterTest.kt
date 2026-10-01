package com.example

import com.example.util.DateOfBirthFormatter
import org.junit.Assert.assertEquals
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
        org.junit.Assert.assertNotNull(millis)
        val formatted = DateOfBirthFormatter.formatFromMillis(millis!!, isRussian = true)
        assertEquals("13 апреля 1998", formatted)
    }
}
