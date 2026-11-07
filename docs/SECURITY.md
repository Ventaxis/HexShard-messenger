# Security posture — HexShard Messenger

Living document, updated after each audit round. Last full audit: **2026-11-07** (two independent auditors: Android client, Supabase backend).

## Verified strengths

- E2EE: ECDH P-256 + AES-256-GCM (128-bit tag, 12-byte IV) + ECDSA SHA-256; v3 envelope with AAD context binding; HMAC integrity check runs **before** GCM decryption
- Hardware Keystore keys, non-exportable, fail-closed (`HardwareKeyNonExportableException`); PBKDF2 600k for backup derivation, constant-time HMAC compare, zeroize on buffers
- TOFU peer-key pinning with quarantine: messages failing signature verification are never shown, they raise a Security Alert
- Local storage: SQLCipher (AES-256) + EncryptedSharedPreferences, fail-closed on master-key failure (`SecurityException`, no plaintext fallback in production)
- Transport: HTTPS only (`usesCleartextTraffic="false"`), no trust-all SSL, no WebView, no JS bridges; QR content strictly validated (`^[a-zA-Z0-9_-]{32,2048}$`) and never opened as URL/intent
- Logging: production build mutes all Timber output; debug-only DebugTree; no tokens/JWT/decrypted payloads logged
- Edge functions: webhook secrets compared as SHA-256 digests (no length leak), single-use atomic challenges, strict persona validation
- No Google services, no analytics, no telemetry — the server only ever sees ciphertext and opaque metadata

## Known trade-offs (tracked, not silently ignored)

| ID | Finding | Severity | Decision |
|---|---|---|---|
| S-1 | Keystore keys are not bound to biometric/PIN (`setUserAuthenticationRequired(false)`) — anyone who unlocks the device can decrypt | MEDIUM | Deferred: requires BiometricPrompt UX in unlock/backup flows. Planned before public release (2.0.0) |
| S-2 | TOFU pins the first peer key with no out-of-band verification — first contact trusts the server | MEDIUM | Deferred: needs a safety-number comparison UI. Planned with S-1 |
| S-3 | No TLS certificate pinning (shared Supabase infrastructure makes upstream pinning fragile) — rogue-CA MITM could swap a peer key at first TOFU contact | MEDIUM | Accepted risk until a dedicated proxy/domain with pinned CA exists; mitigated by server-side RLS and signature quarantine |
| S-4 | Legacy raw-shared-secret attachment decryption fallback still reachable (pre-v3 items) | MEDIUM | Deferred: needs server-side version flag + migration window before removal |
| S-5 | PBKDF2 legacy 100k-iteration read fallback for old backups | LOW | Kept for backward compatibility of old backup reads; new backups use 600k |
| S-6 | Fine location permission for the location-sharing feature | LOW | Feature requires precise coordinates; re-evaluate if the feature scope changes |
| S-7 | Recovery and linking share `telegram_challenges`: a consumed recovery attempt burns the pending linking challenge and vice versa | MEDIUM | Fixed in v1.5.0 (planned): requires the unused `account_recovery_tickets` table — a DB migration round |
| S-8 | Challenge-minting rate limit (webhook `/start <uuid>`) is per-isolate in-memory; multi-isolate bypass possible | MEDIUM→LOW | Fixed in v1.4.0 as an in-window cap (3 mints / 5 min / chat); durable per-telegram limiter deferred with S-7's migration |
| S-9 | `profiles` (incl. public_key/signing_key) readable by all authenticated users via `USING (true)` policy | LOW | Accepted by design: a messenger needs peer-key discovery; only public material is exposed |

## Audit history

- **2026-11-07 (pre-1.4.0):** Android — no CRITICAL/HIGH, 4 MEDIUM (S-1..S-4), 3 LOW (S-5, S-6, backup-error-body logging → fixed same day). Backend — no CRITICAL/HIGH: delete-account ignores client-supplied ids and uses the JWT identity; all RPCs are `auth.uid()`-checked; 3 MEDIUM (S-7, S-8, recovery attempts TOCTOU → **fixed same day** via atomic CAS bump), 3 LOW (PostgREST id interpolation → **fixed same day** via regex guards; web-context system-role injection surface → **fixed same day** as delimited untrusted user message; S-9).
