# HexShard Messenger

> A modern, privacy-first messenger built for speed, security, and simplicity.

![Platform](https://img.shields.io/badge/Platform-Android-brightgreen)
![Language](https://img.shields.io/badge/Kotlin-100%25-blue)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Overview

HexShard Messenger is an Android messaging application focused on:

- 🔒 End-to-End Encryption (E2EE) with Hardware Keystore
- ⚡ Fast, reliable message delivery via Durable Outbox
- 🎨 Modern Material Design 3 UI with Dark Premium Theme
- 🔐 Encrypted local database via SQLCipher
- ☁ Supabase PostgreSQL & Auth Backend
- 🤖 HexShard AI with isolated Ventaxis AI & Hexagon AI personas
- 🔔 Firebase Cloud Messaging (FCM) background wakeups

---

## Features

- End-to-End encrypted direct chats (ECDH P-256 + AES-256-GCM + ECDSA SHA256)
- Supabase GoTrue Authentication & PostgreSQL RLS
- SQLCipher 256-bit AES encrypted local Room database with account scoping
- HexShard AI Assistant (Server-governed Edge Functions)
- Durable Local Outbox queue with retry mechanism
- Real-time message synchronization with idempotent deduplication
- Private Virtual Numbers (+999 format)
- Background Wake-Up Push Notifications
