---
id: SPEC-image-distributor-app
companions: [mechanics.md, ../../planning-artifacts/architecture/architecture-RIS-2026-07-08/ARCHITECTURE-SPINE.md, ../../planning-artifacts/ux-designs/ux-RIS-2026-07-09/DESIGN.md, ../../planning-artifacts/ux-designs/ux-RIS-2026-07-09/EXPERIENCE.md]
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability only — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Scheduled Image Distributor for a Photo-Subscription Business

## Why

The operator (a friend of the requester) runs a business where 8-10 paying customers each expect a daily batch of images over WhatsApp or email. He currently sends these manually, which costs significant time and risks visibly repeating images, which draws customer complaints. This is a pain to solve via automation: a private, sideloaded (not app-store-distributed) tablet/phone app that runs the daily send unattended, gated by a licensing-style compliance check that the requester controls independently of the payment relationship between the operator and his customers.

## Capabilities

- **CAP-1**
  - **intent:** Operator uploads images one at a time; each upload prompts for an optional title and short description before the image is added to the library. Each image can be flagged active or inactive.
  - **success:** An image flagged inactive is never selected for a future send; reactivating it makes it selectable again. Title/description are freely editable at any time (including after upload, via the image's detail view) and never affect selection or delivery — purely descriptive metadata for the operator's own reference.

- **CAP-2**
  - **intent:** Operator can configure up to ~10 receivers, each with a name, +91 mobile number, a delivery channel (WhatsApp or email), and *optionally* one or more daily send schedule times — if any are given, minimum 4. A receiver with no schedule of its own falls back to the app-wide master schedule (also minimum 4 times, configured once in Settings). Each scheduled time sends exactly one image (see CAP-3) — a receiver's daily image count is simply the number of times it's scheduled to send.
  - **success:** Each receiver sends according to its own schedule times (or the master schedule, if it has none) and channel — changing one receiver's settings never affects another's, and changing the master schedule only affects receivers that don't have their own.

- **CAP-3**
  - **intent:** At each scheduled send, the system selects exactly one active image for the receiver, avoiding any image already sent to that receiver in the last 7 days unless the eligible pool is exhausted (see `mechanics.md`).
  - **success:** Two different scheduled sends to the same receiver produce different images; no receiver receives an image already sent to them within the prior 7 days, except when every active image has already been sent to them within that window, in which case a repeat is allowed. If the operator has no active images at all, that scheduled slot simply sends nothing.

- **CAP-4**
  - **intent:** Selected sends are queued by image ID (not image data) and delivered over the receiver's channel; failed deliveries retry automatically and resume once connectivity returns.
  - **success:** A send that fails for lack of network retries up to 3 times and completes once connectivity is restored, with no operator action; if the tablet is offline through an entire scheduled window, only that day's batch sends on reconnect — earlier missed days are not backfilled.

- **CAP-5**
  - **intent:** Operator can view, per receiver, a log of what was sent and when, covering at least the last 30 days.
  - **success:** For any receiver and any day within the last 30 days, the dashboard shows the timestamp of each image sent — enough to resolve a customer's "I didn't get my images" dispute.

- **CAP-6**
  - **intent:** On first launch, the app collects the installer's ("customer") first name/nickname and city once, locks them permanently, and registers the install with an external admin system.
  - **success:** First name/nickname and city cannot be edited after first entry; the same values are POSTed to the admin's registration endpoint at first-run time.

- **CAP-7**
  - **intent:** The app checks a hardcoded compliance API using the registered first name/nickname and city, and halts sending only on an explicit non-compliant response — continuing to operate on every other outcome.
  - **success:** An explicit non-compliant response halts all sending immediately with a "contact admin" message; an unreachable or failing compliance API call does not halt sending — it retries at the next scheduled check while sends continue normally in the meantime.

- **CAP-8**
  - **intent:** Transmission history older than a configurable retention period (default 30 days) is purged from the database.
  - **success:** Records older than the configured window are removed; changing the configured window changes what gets purged going forward.

- **CAP-9**
  - **intent:** Operator can configure one app-wide default ("master") schedule of daily send times (minimum 4) in Settings, used by any receiver that has no schedule of its own.
  - **success:** A receiver with no per-receiver schedule sends at the master schedule's times; changing the master schedule immediately changes future sends for every receiver still using it, but never touches receivers that have their own schedule.

## Constraints

- App data persists on-device in SQLite (via Room) only; no cloud database dependency and no separate database server (Android cannot practically run a full PostgreSQL server on-device, and SQLite comfortably covers the required scale).
- App is distributed by direct package transfer (sideloaded) to the installer, never via Play Store/App Store; no store-based distribution or update mechanism.
- Receiver mobile numbers assume a fixed +91 country code.
- Delivery channels are limited to WhatsApp, via the WhatsApp Business API (Cloud API), and email, via Google/Gmail SMTP — no other channels.
- The compliance API endpoint is hardcoded into the app binary, not user-configurable; no authentication is required on the compliance-check or install-registration API calls.
- Only a first name/nickname is collected from the installer, never a full legal name — kept deliberately minimal so this is not treated as PII requiring dedicated data-protection handling.
- Once an image is selected and queued for a send, it is still delivered even if later flagged inactive before the queue fires; "inactive" only blocks future selection.
- Fail-open by design: only an explicit non-compliant response may halt sending. Every other failure mode (compliance API unreachable/timeout, active-image pool smaller than the chosen count, 7-day no-repeat pool exhausted) must degrade gracefully rather than block. See `mechanics.md` for the full failure-mode table.
- Failed transmissions retry a maximum of 3 times.

## Non-goals

- Payment/billing between the operator and his receivers/customers — handled entirely outside this app.
- An admin interface for managing compliance status inside this app — that happens in a separate external system, manually and offline.
- Generating, editing, or curating images — the app only distributes images the operator uploads.
- Play Store/App Store distribution or any store-based update mechanism.

## Success signal

The operator configures his image library and up to 10 receivers once; from then on, for at least 30 consecutive days, every receiver gets its randomized daily batch on schedule with zero manual effort, no repeat image within 7 days barring pool exhaustion, and automatic recovery from offline gaps. If a customer disputes delivery, the operator can pull up a timestamped log proving it was sent. The app stops sending only when the compliance API explicitly reports the install as not compliant.

## Assumptions

- Assumed target platform is Android, since direct APK-style sideload distribution without an app store is impractical on iOS without enterprise provisioning.
- Assumed +91 is fixed/hardcoded for all receivers, not a per-receiver selectable country code.
