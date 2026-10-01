package com.example.data

enum class AccountType(val id: String) {
    STANDARD("standard"),
    PHONE("phone");

    companion object {
        fun fromId(id: String?): AccountType {
            return if (id.equals("standard", ignoreCase = true)) STANDARD else PHONE
        }
    }
}

