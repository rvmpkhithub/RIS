---
review: version/reality-check of ARCHITECTURE-SPINE.md Stack table
reviewed_file: '../ARCHITECTURE-SPINE.md'
date: 2026-07-09
reviewer_lens: 'verify every committed tech/version decision was web-researched, not asserted from training data'
---

# Review: Stack Table Version Claims

Context: review conducted July 2026, against the claim in the spine that this reflects "current month July 2026" decisions.

## Claim-by-claim findings

### Kotlin 2.4.0 — CONFIRMED
Real and current. JetBrains released Kotlin 2.4.0 in June 2026 (blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/). Stable context parameters, explicit backing fields, Java 26 support, CMS GC default for Native. Matches a July-2026 "current" pick well — released just one month prior.

### Jetpack Compose BOM 2026.04.01 — CONFIRMED but STALE
The BOM 2026.04.01 is real (April '26 release, developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-april-26-release, core Compose 1.11). Not fabricated. **However**, by July 2026 there are at least two newer BOMs out: 2026.06.01 (June, tracking Compose 1.11.4) and a 2026.06.00 also referenced, plus a 1.12.0-beta02 track. A spine written "as of July 2026" naming an April BOM as current is two months stale — this reads like it was picked without checking for the latest release rather than genuinely current. **Flag: should be updated to the June (or later) BOM, or the doc should explicitly justify pinning to April.**

### Android Gradle Plugin 9.2.0 — CONFIRMED
Real, dated April 2026 (developer.android.com/build/releases/agp-9-2-0-release-notes), max API level 37. Plausible and current for a July 2026 build.

### Room 2.6.x — FLAGGED: STALE / LIKELY TRAINING-DATA ARTIFACT
This is the strongest flag in the table. As of 2026:
- Room 2.x is in **maintenance mode** — the 2.x line has moved well past 2.6. Current stable is around **2.8.x** (2.8.4 seen in package listings), with `androidx.room:room-sqlite-wrapper` added in 2.8.0. Room 2.7.0+ already introduced the new driver APIs.
- More importantly, **Room 3.0** exists and is the actively-developed line: first alpha shipped March 2026 (android-developers.googleblog.com/2026/03/room-30-modernizing-room.html), a major breaking rewrite — Kotlin-only generated code, coroutine-only APIs, KMP/JS/Wasm support, new `androidx.room3` package to avoid colliding with 2.x.
- "Room 2.6.x" is the version that was current/stable roughly in 2023–2024 (matches an LLM's likely training-data default), not July 2026 reality. This looks asserted from memory rather than checked. **The spine should either commit to current Room 2.x (2.8.x, maintenance-mode) or explicitly consider/reject Room 3.0 (coroutines-only, KMP) — right now it names neither correctly.**

### WorkManager 2.11.2 — UNABLE TO FULLY CONFIRM, PLAUSIBLE
Could not independently verify the exact stable version number 2.11.2 via search — results were generic and didn't surface a specific changelog entry for that patch version. Did confirm WorkManager is actively shipping releases as of March 2026 (multiple artifacts: work-runtime, work-rxjava2/3, work-gcm, work-testing, work-multiprocess all updated together). The version number is plausible in shape (2.11.x is a reasonable place for WorkManager to be by mid-2026 given its release cadence) but **not confirmed against a primary source** — treat as unverified rather than confirmed.

### Retrofit + OkHttp 5.0 — FLAGGED: INTERNALLY INCONSISTENT
- OkHttp 5.0 is real — described as Square's "first stable release since 2023," with separate JVM/Android artifacts and a zstd module. Confirmed.
- Retrofit's latest stable is **3.0.0** (released ~May 2025, fully rewritten in Kotlin, requires Java 8+/API 21+) — but Retrofit 3.0.0 ships with **OkHttp 4.12** as its transitive dependency by default, not OkHttp 5.0. OkHttp 4 and 5 are binary-compatible, so a project *can* force OkHttp 5.0 alongside Retrofit 3.0.0, but that's a manual override, not the shipped pairing.
- The stack table entry **"Retrofit + OkHttp 5.0"** never states Retrofit's own version number, and implies a paired "Retrofit+OkHttp 5.0" combo that doesn't exist as a default artifact combination. **This is either sloppy shorthand (meaning "Retrofit 3.x, with OkHttp force-upgraded to 5.0") or a real inconsistency — the table should name Retrofit's version explicitly (3.0.0) and note that OkHttp 5.0 requires an explicit override.**

### Jakarta Mail (Gmail SMTP) — version deferred, defensible; auth approach unaddressed
The table doesn't commit to a version ("latest stable at build time"), so there's no fabricated number to check. Confirmed Jakarta Mail 2.0+ does support running on Android (API 19+) with built-in OAuth2 support (useful since SASL isn't available on Android) — so the library choice itself is sound.
**Gap, not a version issue:** neither the stack table nor AD-3 specifies the SMTP *authentication* method. Reality check on Gmail SMTP: Google has continued tightening auth — plain account passwords have been rejected since "Less Secure Apps" was retired (2022); as of 2026 only **16-character App Passwords** (personal + 2FA) or **OAuth 2.0** (Workspace) work, and Google's own docs call App Passwords "not recommended." Port 587/smtp.gmail.com itself is still live and correct. **Recommend the spine name which auth mode (App Password vs OAuth2) `AppConfig.kt` will hold a reference to — this affects AD-3's "SMTP host" wording, which currently omits credentials/auth entirely.**

### WhatsApp Business Cloud API — CONFIRMED, correct decision
Confirmed as the only viable path in 2026: the legacy On-Premises API was deprecated October 23, 2025, making Cloud API mandatory for all new integrations. Good, current, well-grounded decision — no flag.

### Gmail SMTP smtp.gmail.com:587 — CONFIRMED, still functional
Confirmed still live and accepting connections in 2026. See auth-method gap noted above under Jakarta Mail.

## Summary of flags

| Item | Status |
| --- | --- |
| Kotlin 2.4.0 | Confirmed, current |
| Compose BOM 2026.04.01 | Confirmed real, but stale (June/later BOMs exist) |
| AGP 9.2.0 | Confirmed, current |
| Room 2.6.x | **Flagged — stale, likely un-researched.** Current Room 2.x is ~2.8.x (maintenance mode); Room 3.0 (coroutines-only, KMP) exists and isn't discussed |
| WorkManager 2.11.2 | Unconfirmed exact version; plausible, not verified against a primary source |
| Retrofit + OkHttp 5.0 | **Flagged — internally inconsistent.** Retrofit's own version unstated; latest Retrofit (3.0.0) ships with OkHttp 4.12 by default, not 5.0 |
| Jakarta Mail | No version committed (fine); auth method (App Password vs OAuth2) unaddressed — gap |
| WhatsApp Business Cloud API | Confirmed, correct/only viable option |
| Gmail SMTP 587 | Confirmed still functional |
