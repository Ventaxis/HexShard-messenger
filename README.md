# HexShard Messenger

<div align="center">

[![Build HexShard](https://github.com/Ventaxis/HexShard-messenger/actions/workflows/build.yml/badge.svg?branch=main-fixed)](https://github.com/Ventaxis/HexShard-messenger/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Ventaxis/HexShard-messenger?style=flat-square&label=release)](https://github.com/Ventaxis/HexShard-messenger/releases)
[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen)](#)
[![Language](https://img.shields.io/badge/Kotlin%20%2B%20TypeScript-blue)](#)
[![Security](https://img.shields.io/badge/Security-audited%20%2B%20tracked-8A2BE2)](docs/SECURITY.md)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

### **HexShard. Мессенджер, который не знает о тебе ничего.**

**The messenger that knows nothing about you.**

Privacy-first Android messenger: end-to-end encryption with hardware-bound keys,
encrypted local storage, anonymous virtual identities, and a Supabase backend that
only ever sees ciphertext.

**[📥 Download the latest APK](https://github.com/Ventaxis/HexShard-messenger/releases/latest)**

</div>

---

## What's inside

| | Feature | How it's real |
|---|---|---|
| 🔒 | **End-to-End Encryption** | ECDH P-256 + AES-256-GCM + ECDSA SHA-256; private keys are **non-exportable, hardware-bound** (Android Keystore). Fail-closed: no exportable key, no backup |
| 🧬 | **Encrypted at rest** | SQLCipher (AES-256) local database + EncryptedSharedPreferences, account-scoped |
| 🕵️ | **Zero Google services** | No GMS, no analytics, no telemetry — pure Supabase (PostgreSQL, Auth, Realtime, Storage) |
| 📞 | **+999 virtual numbers** | Anonymous identities without a phone number or SIM |
| 📨 | **Durable outbox** | Messages survive crashes, offline gaps and retries; idempotent delivery with tombstones |
| 🛡️ | **Signature quarantine** | Messages failing peer-signature verification are never displayed — they raise a Security Alert |
| 🤖 | **AI personas** | Ventaxis AI & Hexagon AI served by server-governed edge functions on Groq, strict persona validation, per-user quotas |
| 📡 | **Realtime sync** | Supabase WebSockets with paginated catch-up, per-page cursor checkpointing and token-refresh queueing |

## Architecture

- **Client**: 100% Kotlin, single Activity, Jetpack Compose (Material 3), Room + SQLCipher, Hilt, CameraX, zxing
- **Backend**: 8 Deno edge functions — Telegram auth (challenge/verify/recovery), virtual number reservation, AI proxy with quota, account deletion
- **Database**: PostgreSQL with strict RLS — every RPC is `auth.uid()`-checked; recovery uses dedicated single-use tickets
- **CI/CD**: every push runs unit tests (Robolectric) + edge-function tests (Deno); every `v*` tag auto-publishes a GitHub Release with an installable APK

## Security

Security is tracked, audited and honest — see [docs/SECURITY.md](docs/SECURITY.md) for the
full posture, the verified strengths and every known trade-off (nothing is silently ignored).

Highlights: hardware-key fail-closed design · HMAC integrity before GCM decryption ·
TOFU peer-key pinning with quarantine · webhook secrets compared as SHA-256 digests ·
single-use atomic challenges · two-axis security audits with no CRITICAL/HIGH findings.

## Testing

- `app/src/test` — Robolectric unit tests (crypto safety, vault preservation, sync cursors, MIME policy, virtual number identity)
- `supabase/functions/_tests` — Deno suite for the edge functions (persona validation, webhook auth, recovery tickets, rate limits)
- Both suites run in CI on every push

## License

This project is licensed under the [MIT License](LICENSE).

Copyright (c) 2026 Ventaxis (Ventaxis Corp)
