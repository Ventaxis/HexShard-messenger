package com.example.util

/**
 * Utility for parsing, validating, and formatting +999 virtual private numbers.
 * NOTE: +999 numbers are STRICTLY allocated and validated on the Supabase backend.
 * Client-side generation of virtual numbers is prohibited.
 */
object VirtualNumberGenerator {

    /**
     * Formats 8 raw digits into "+999 XXXX XXXX".
     */
    fun format8Digits(digits: String): String {
        val clean = digits.filter { it.isDigit() }
        if (clean.length != 8) return "+999 $clean"
        return "+999 ${clean.substring(0, 4)} ${clean.substring(4, 8)}"
    }

    /**
     * Extracts exactly 8 raw digits from user input or pasted string.
     * Handles:
     * - "67676767" -> "67676767"
     * - "+999 6767 6767" -> "67676767"
     * - "+99967676767" -> "67676767"
     * Returns null if input does not contain exactly 8 valid digits.
     */
    fun extractRaw8Digits(input: String): String? {
        val clean = input.trim()
        val digits = clean.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("999") -> digits.substring(3)
            digits.length == 8 -> digits
            else -> null
        }
    }

    /**
     * Validates whether 8 digits are valid for virtual number.
     */
    fun isValid8Digits(digits: String): Boolean {
        val clean = digits.filter { it.isDigit() }
        return clean.length == 8
    }

    /**
     * Determines if a phone string is a virtual guest number.
     */
    fun isVirtual(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val clean = phone.filter { it.isDigit() || it == '+' }
        return clean.startsWith("+999") || phone.contains("Virtual", ignoreCase = true)
    }
}

