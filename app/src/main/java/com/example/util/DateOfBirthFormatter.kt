package com.example.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Utility for formatting, parsing, and validating birth dates.
 *
 * CANONICAL / DATABASE STORAGE:
 * Strict ISO 8601: "YYYY-MM-DD" (e.g. "1998-04-13").
 *
 * USER DISPLAY:
 * Russian: "13 апреля 1998"
 * English: "13 April 1998"
 */
object DateOfBirthFormatter {

    val RU_MONTHS_GENITIVE = arrayOf(
        "", "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )

    val RU_MONTHS_NOMINATIVE = arrayOf(
        "", "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    )

    val EN_MONTHS = arrayOf(
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    fun getMonthName(month: Int, isRussian: Boolean): String {
        return if (isRussian) {
            if (month in 1..12) RU_MONTHS_GENITIVE[month] else month.toString()
        } else {
            if (month in 1..12) EN_MONTHS[month] else month.toString()
        }
    }

    /**
     * Leap year rule:
     * Divisible by 4 and not by 100, or divisible by 400.
     */
    fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    /**
     * Exact number of days in the given month and year.
     */
    fun getDaysInMonth(month: Int, year: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 0
        }
    }

    /**
     * Strict calendar date validation.
     * Validates:
     * - Year within 1900..currentYear+1
     * - Month within 1..12
     * - Day within 1..getDaysInMonth(month, year)
     *
     * Correct behavior:
     * 29.02.2024 -> true (leap)
     * 29.02.2023 -> false
     * 31.04.2024 -> false (April has 30 days)
     * 00.01.2020 -> false
     * 32.01.2020 -> false
     */
    fun isValidDate(day: Int, month: Int, year: Int): Boolean {
        val currentYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.YEAR)
        if (year !in 1900..(currentYear + 1)) return false
        if (month !in 1..12) return false
        val maxDays = getDaysInMonth(month, year)
        return day in 1..maxDays
    }

    /**
     * Parses any recognized date string into (day, month, year).
     * Supports:
     * 1. ISO 8601: "YYYY-MM-DD"
     * 2. Delimited: "DD.MM.YYYY", "DD/MM/YYYY", "DD-MM-YYYY"
     * 3. Textual with Russian or English month names: "13 апреля 1998", "13 April 1998"
     * 4. Pure 8 digits: "13041998" (DDMMYYYY)
     *
     * Returns null if the string cannot be parsed or if the date is calendar-invalid.
     */
    fun parseParts(input: String): Triple<Int, Int, Int>? {
        val clean = input.trim()
        if (clean.isBlank()) return null

        // 1. Check for textual month names (Russian genitive/nominative & English)
        for (m in 1..12) {
            val ruGen = RU_MONTHS_GENITIVE[m]
            val ruNom = RU_MONTHS_NOMINATIVE[m]
            val en = EN_MONTHS[m]

            val matchedPattern = when {
                ruGen.isNotEmpty() && clean.contains(ruGen, ignoreCase = true) -> ruGen
                ruNom.isNotEmpty() && clean.contains(ruNom, ignoreCase = true) -> ruNom
                en.isNotEmpty() && clean.contains(en, ignoreCase = true) -> en
                else -> null
            }

            if (matchedPattern != null) {
                val replaced = clean.replace(Regex("(?i)$matchedPattern"), " ")
                val digitTokens = replaced.split(Regex("[^0-9]+")).filter { it.isNotBlank() }
                if (digitTokens.size >= 2) {
                    val day = digitTokens[0].toIntOrNull()
                    val year = digitTokens[1].toIntOrNull()
                    if (day != null && year != null && isValidDate(day, m, year)) {
                        return Triple(day, m, year)
                    }
                }
                // Text matched month name but failed date check
                return null
            }
        }

        // 2. Delimited: '.', '/', '-'
        val delimiterTokens = clean.split('.', '/', '-')
        if (delimiterTokens.size == 3) {
            val t0 = delimiterTokens[0].trim().toIntOrNull()
            val t1 = delimiterTokens[1].trim().toIntOrNull()
            val t2 = delimiterTokens[2].trim().toIntOrNull()
            if (t0 != null && t1 != null && t2 != null) {
                // Check if YYYY-MM-DD
                if (t0 > 1000) {
                    val year = t0
                    val month = t1
                    val day = t2
                    if (isValidDate(day, month, year)) {
                        return Triple(day, month, year)
                    }
                }
                // Check if DD.MM.YYYY
                if (t2 > 1000) {
                    val day = t0
                    val month = t1
                    val year = t2
                    if (isValidDate(day, month, year)) {
                        return Triple(day, month, year)
                    }
                }
            }
            return null
        }

        // 3. Pure 8 digits: "13041998" (DDMMYYYY)
        val pureDigits = clean.filter { it.isDigit() }
        if (pureDigits.length == 8) {
            val day = pureDigits.substring(0, 2).toIntOrNull()
            val month = pureDigits.substring(2, 4).toIntOrNull()
            val year = pureDigits.substring(4, 8).toIntOrNull()
            if (day != null && month != null && year != null && isValidDate(day, month, year)) {
                return Triple(day, month, year)
            }
        }

        return null
    }

    /**
     * Converts any valid date format into canonical ISO "YYYY-MM-DD".
     * Returns null if invalid or blank.
     */
    fun toCanonicalIso(input: String): String? {
        val clean = input.trim()
        if (clean.isBlank()) return null
        val parts = parseParts(clean) ?: return null
        return String.format(Locale.US, "%04d-%02d-%02d", parts.third, parts.second, parts.first)
    }

    /**
     * Formats date for UI display (e.g. "13 апреля 1998" or "13 April 1998").
     * Supports canonical ISO strings, legacy localized strings, and digit strings.
     */
    fun formatForDisplay(rawInput: String, isRussian: Boolean = true): String {
        val clean = rawInput.trim()
        if (clean.isBlank()) return ""
        val parts = parseParts(clean)
        return if (parts != null) {
            formatFromParts(parts.first, parts.second, parts.third, isRussian)
        } else {
            // If cannot parse, return empty or trimmed string
            clean
        }
    }

    /**
     * Formats day, month, year into UI display string.
     */
    fun formatFromParts(day: Int, month: Int, year: Int, isRussian: Boolean = true): String {
        val monthName = getMonthName(month, isRussian)
        return "$day $monthName $year"
    }

    /**
     * Formats day, month, year into canonical ISO string.
     */
    fun formatPartsToIso(day: Int, month: Int, year: Int): String {
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * Parses any recognized date string and returns UTC milliseconds epoch, or null if invalid.
     */
    fun parseToMillis(input: String): Long? {
        val parts = parseParts(input) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(parts.third, parts.second - 1, parts.first)
        }
        return cal.timeInMillis
    }

    /**
     * Formats UTC milliseconds into textual display string.
     */
    fun formatFromMillis(millis: Long, isRussian: Boolean = true): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = millis
        }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        return formatFromParts(day, month, year, isRussian)
    }

    /**
     * Formats UTC milliseconds into canonical ISO string.
     */
    fun formatFromMillisToIso(millis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = millis
        }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        return formatPartsToIso(day, month, year)
    }

    /**
     * Auto-formats during manual typing.
     */
    fun autoFormatTyping(input: String, isRussian: Boolean = true): String {
        val clean = input.trim()
        if (clean.isBlank()) return ""

        // Delimited or pure digits
        val parts = parseParts(clean)
        if (parts != null) {
            return formatFromParts(parts.first, parts.second, parts.third, isRussian)
        }

        // Partial typing fallback
        val digitsOnly = clean.filter { it.isDigit() }
        if (digitsOnly.length in 4..7) {
            val day = digitsOnly.substring(0, 2).toIntOrNull()
            val month = digitsOnly.substring(2, 4).toIntOrNull()
            val rest = digitsOnly.substring(4)
            if (day != null && month != null && month in 1..12 && day in 1..31) {
                val mName = getMonthName(month, isRussian)
                return if (rest.isNotBlank()) "$day $mName $rest" else "$day $mName"
            }
        }

        return clean
    }

    /**
     * Validates if the input represents a valid date.
     */
    fun isValid(input: String): Boolean {
        return parseParts(input) != null
    }
}
