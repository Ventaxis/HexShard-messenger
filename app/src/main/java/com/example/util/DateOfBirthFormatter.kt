package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Utility for formatting and parsing birth dates with textual month representation
 * (e.g. "13 апреля 1998" or "13 April 1998").
 * Ensures birthdays are displayed in an elegant, human-readable format rather than raw digits (e.g. 13041998).
 */
object DateOfBirthFormatter {

    val RU_MONTHS_GENITIVE = arrayOf(
        "", "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
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

    fun getDaysInMonth(month: Int, year: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 31
        }
    }

    fun formatFromParts(day: Int, month: Int, year: Int, isRussian: Boolean): String {
        val safeMonth = month.coerceIn(1, 12)
        val maxDays = getDaysInMonth(safeMonth, year)
        val safeDay = day.coerceIn(1, maxDays)
        return formatParts(safeDay, safeMonth, year, isRussian)
    }

    /**
     * Formats an epoch millisecond timestamp (from DatePickerDialog) into a textual date.
     */
    fun formatFromMillis(millis: Long, isRussian: Boolean = true): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = millis
        }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1 // 1-based
        val year = cal.get(Calendar.YEAR)
        return formatParts(day, month, year, isRussian)
    }

    /**
     * Parses an input string (digits, dotted, dashed, or textual) and returns millisecond epoch if valid.
     */
    fun parseToMillis(input: String): Long? {
        val clean = input.trim()
        if (clean.isBlank()) return null

        // Try parsing textual format first
        val parts = parseParts(clean)
        if (parts != null) {
            val (day, month, year) = parts
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(year, month - 1, day)
            }
            return cal.timeInMillis
        }

        // Try standard format patterns as fallback
        val patterns = arrayOf(
            "dd.MM.yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "dd-MM-yyyy"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Takes any stored date representation (e.g. "13041998", "13.04.1998", "1998-04-13",
     * or already formatted text) and converts it to the elegant textual representation.
     */
    fun formatForDisplay(rawInput: String, isRussian: Boolean = true): String {
        val clean = rawInput.trim()
        if (clean.isBlank()) return ""

        val parts = parseParts(clean)
        if (parts != null) {
            val (day, month, year) = parts
            return formatParts(day, month, year, isRussian)
        }

        // If it already matches textual format, return as is
        return clean
    }

    /**
     * Auto-formats during manual typing. Converts digit sequences (e.g. "13041998", "13.04.1998",
     * or partial "1304") into textual month representations ("13 апреля 1998").
     */
    fun autoFormatTyping(input: String, isRussian: Boolean = true): String {
        val clean = input.trim()
        if (clean.isBlank()) return ""

        // Check if string contains already month text
        var matchedMonth: Int? = null
        for (m in 1..12) {
            val ru = RU_MONTHS_GENITIVE[m]
            val en = EN_MONTHS[m]
            if ((ru.isNotEmpty() && clean.contains(ru, ignoreCase = true)) ||
                (en.isNotEmpty() && clean.contains(en, ignoreCase = true))) {
                matchedMonth = m
                break
            }
        }

        if (matchedMonth != null) {
            // Already has month name, e.g. "13 апреля" or "13 апреля 1998"
            val mName = getMonthName(matchedMonth, isRussian)
            val parts = clean.split(Regex("(?i)$mName|\\s+")).filter { it.isNotBlank() }
            val dayPart = parts.firstOrNull()?.filter { it.isDigit() }?.toIntOrNull()
            val yearPart = if (parts.size > 1) parts[1].filter { it.isDigit() } else ""
            if (dayPart != null) {
                return if (yearPart.isNotBlank()) {
                    "$dayPart $mName $yearPart"
                } else {
                    "$dayPart $mName"
                }
            }
            return clean
        }

        // Delimited formats like "13.04.1998", "13/04/1998", "13 04 1998"
        val tokens = clean.split(Regex("[./\\-\\s]+")).filter { it.isNotBlank() }
        if (tokens.size >= 2) {
            val day = tokens[0].filter { it.isDigit() }.toIntOrNull()
            val month = tokens[1].filter { it.isDigit() }.toIntOrNull()
            val yearStr = tokens.getOrNull(2)?.filter { it.isDigit() } ?: ""
            if (day != null && month != null && month in 1..12 && day in 1..31) {
                val mName = getMonthName(month, isRussian)
                return if (yearStr.isNotBlank()) "$day $mName $yearStr" else "$day $mName"
            }
        }

        // Pure digits: e.g. "13041998", "1304", "130419"
        val digitsOnly = clean.filter { it.isDigit() }
        if (digitsOnly.length == 8) {
            val day = digitsOnly.substring(0, 2).toIntOrNull()
            val month = digitsOnly.substring(2, 4).toIntOrNull()
            val year = digitsOnly.substring(4, 8).toIntOrNull()
            if (day != null && month != null && year != null && month in 1..12 && day in 1..31) {
                return "$day ${getMonthName(month, isRussian)} $year"
            }
        }

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

    fun parseParts(input: String): Triple<Int, Int, Int>? {
        val clean = input.trim()

        // 1. Text format like "13 апреля 1998" or "13 April 1998"
        for (m in 1..12) {
            val ruName = RU_MONTHS_GENITIVE[m]
            if (clean.contains(ruName, ignoreCase = true)) {
                val digits = clean.replace(ruName, " ").split(Regex("\\s+")).filter { it.isNotBlank() }
                if (digits.size >= 2) {
                    val day = digits[0].toIntOrNull() ?: 1
                    val year = digits[1].toIntOrNull() ?: 2000
                    if (isValidDate(day, m, year)) return Triple(day, m, year)
                }
            }
            val enName = EN_MONTHS[m]
            if (clean.contains(enName, ignoreCase = true)) {
                val digits = clean.replace(enName, " ").split(Regex("\\s+")).filter { it.isNotBlank() }
                if (digits.size >= 2) {
                    val day = digits[0].toIntOrNull() ?: 1
                    val year = digits[1].toIntOrNull() ?: 2000
                    if (isValidDate(day, m, year)) return Triple(day, m, year)
                }
            }
        }

        // 2. Pure 8 digits: "13041998"
        val pureDigits = clean.filter { it.isDigit() }
        if (pureDigits.length == 8) {
            val day = pureDigits.substring(0, 2).toIntOrNull()
            val month = pureDigits.substring(2, 4).toIntOrNull()
            val year = pureDigits.substring(4, 8).toIntOrNull()
            if (day != null && month != null && year != null && isValidDate(day, month, year)) {
                return Triple(day, month, year)
            }
        }

        // 3. Delimited by dots, slashes, or hyphens: "13.04.1998" or "1998-04-13"
        val tokens = clean.split('.', '/', '-')
        if (tokens.size == 3) {
            val t0 = tokens[0].trim().toIntOrNull()
            val t1 = tokens[1].trim().toIntOrNull()
            val t2 = tokens[2].trim().toIntOrNull()
            if (t0 != null && t1 != null && t2 != null) {
                // Check if YYYY-MM-DD
                if (t0 > 1000 && isValidDate(t2, t1, t0)) {
                    return Triple(t2, t1, t0)
                }
                // DD.MM.YYYY
                if (t2 > 1000 && isValidDate(t0, t1, t2)) {
                    return Triple(t0, t1, t2)
                }
            }
        }

        return null
    }

    private fun formatParts(day: Int, month: Int, year: Int, isRussian: Boolean): String {
        return if (isRussian) {
            val monthName = if (month in 1..12) RU_MONTHS_GENITIVE[month] else month.toString()
            "$day $monthName $year"
        } else {
            val monthName = if (month in 1..12) EN_MONTHS[month] else month.toString()
            "$day $monthName $year"
        }
    }

    private fun isValidDate(day: Int, month: Int, year: Int): Boolean {
        if (year !in 1900..2026) return false
        if (month !in 1..12) return false
        val maxDays = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 31
        }
        return day in 1..maxDays
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
