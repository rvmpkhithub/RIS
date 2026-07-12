---
stepsCompleted: [step-01, step-02, step-03, step-04]
inputDocuments: ['../specs/spec-image-distributor-app/SPEC.md', '../specs/spec-image-distributor-app/mechanics.md', './architecture/architecture-RIS-2026-07-08/ARCHITECTURE-SPINE.md']
---

# RIS - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for the scheduled image-distributor app, decomposing the requirements from `SPEC.md` (spec-image-distributor-app) and the finalized `ARCHITECTURE-SPINE.md` into implementable stories. No UX design document was produced for this project (single-operator utility app, no dedicated UX design phase run).

## Requirements Inventory

### Functional Requirements

FR1: Operator can upload a library of images and flag each one active or inactive. (CAP-1)
FR2: Operator can configure up to ~10 receivers, each with a name, +91 mobile number, a delivery channel (WhatsApp or email), a per-receiver min/max image count (drawn fresh at each send), and one or more daily send schedule times (minimum 4). (CAP-2)
FR3: At each scheduled send, the system picks a random image count within that receiver's min/max and selects that many active images, avoiding any image already sent to that receiver in the last 7 days unless the eligible pool can't cover the count. (CAP-3)
FR4: Selected sends are queued by image ID (not image data) and delivered over the receiver's channel; failed deliveries retry automatically (up to 3 times) and resume once connectivity returns. If offline through an entire scheduled window, only that day's batch resends on reconnect — no backfill of earlier missed days. (CAP-4)
FR5: Operator can view, per receiver, a log of what was sent and when, covering at least the last 30 days, sufficient to resolve delivery disputes. (CAP-5)
FR6: On first launch, the app collects the installer's first name/nickname and city once, locks them permanently, and registers the install with an external admin system. (CAP-6)
FR7: The app checks a hardcoded compliance API using the registered first name/nickname and city, and halts sending only on an explicit non-compliant response — continuing to operate on every other outcome (including the API being unreachable). (CAP-7)
FR8: Transmission history older than a configurable retention period (default 30 days) is purged from the database. (CAP-8)

### NonFunctional Requirements

NFR1: App data persists on-device in SQLite (via Room) only; no cloud database, no separate database server.
NFR2: App is distributed by direct APK sideload transfer, never via Play Store/App Store; no store-based distribution, and no in-app update mechanism (updates are manual APK resend + reinstall).
NFR3: Receiver mobile numbers assume a fixed +91 country code.
NFR4: Delivery channels are limited to WhatsApp (via WhatsApp Business Cloud API) and email (via Gmail SMTP) — no other channels.
NFR5: The compliance API endpoint is hardcoded into the app binary; no authentication is required on the compliance-check or install-registration API calls.
NFR6: Fail-open by design: only an explicit non-compliant response may halt sending; every other failure mode (compliance API unreachable/timeout, active-image pool smaller than the chosen count, 7-day no-repeat pool exhausted) must degrade gracefully instead of blocking.
NFR7: Failed transmissions retry a maximum of 3 times.
NFR8: Once an image is selected and queued for a send, it is still delivered even if later flagged inactive before the queue fires.

### Additional Requirements

- Layered MVVM paradigm with no backend server; all external calls (compliance API, WhatsApp Business Cloud API, Gmail SMTP) originate directly on-device. (AD-1, AD-2)
- Single source of truth for all external endpoints/hardcoded config in `config/AppConfig.kt` — never inlined elsewhere. (AD-3)
- Background execution exclusively via WorkManager: one periodic scanning Worker (not per-receiver work requests) checks each receiver's schedule on a short fixed interval; app requests the "ignore battery optimizations" exemption once at setup. (AD-4, AD-12)
- Repository is the sole mutation path for data access; business rules (selection algorithm, compliance gating, retention policy) live only in dedicated domain services (`ImageSelectionEngine`, `ComplianceGate`, `RetentionPolicy`), never duplicated in ViewModels/Workers. (AD-5, AD-10)
- IDs are `Long` autoincrement (no UUIDs); timestamps stored as epoch-millis, converted to `Instant` at the domain boundary; errors surfaced as `AppResult<T>`, never raw exceptions crossing the Repository boundary. (AD-6, AD-7, AD-8)
- No in-app update-check code path exists; updates are fully manual (rebuild + resend APK, manual reinstall). (AD-9)
- `COMPLIANCE_STATE.isCompliant`/`lastCheckedAt` is a display-only cache for offline UI status — `ComplianceGate` always performs a live check when online and never gates on the cached value. (AD-11)
- `Transmission` is one row per queued send, updated in place across retries (not one row per attempt), to avoid double-counting on the dashboard. (AD-13)
- Uploaded images are stored in app-private internal storage (`context.filesDir/images/`), referenced by relative filename — no MediaStore/content-URI, no storage permission needed. (AD-14)
- Once real data exists on the friend's device, every Room schema change ships an explicit `Migration`; `fallbackToDestructiveMigration()` must never be used against an install with real data. (AD-15)
- Stack: Kotlin 2.4.0, Jetpack Compose (BOM 2026.06.01), Android Gradle Plugin 9.2.0, Room 2.8.x, WorkManager 2.10.x, Retrofit 3.0.0 (bundled OkHttp 4.12), Jakarta Mail via Gmail SMTP (App Password auth), manual DI (`AppContainer`, no framework).
- Source tree seed: `app/src/main/java/com/ris/imagedistributor/{ui,domain,data/{local,remote,repository},worker,di,config}/`.

### UX Design Requirements

N/A — no UX design document was produced for this project.

### FR Coverage Map

| Requirement | Capability | Covered by Epic |
| --- | --- | --- |
| FR1 | CAP-1 | Epic 1 |
| FR2 | CAP-2 | Epic 1 |
| FR3 | CAP-3 | Epic 2 |
| FR4 | CAP-4 | Epic 2 |
| FR5 | CAP-5 | Epic 3 |
| FR6 | CAP-6 | Epic 1 |
| FR7 | CAP-7 | Epic 1 |
| FR8 | CAP-8 | Epic 3 |

## Epic List

### Epic 1: Setup — Onboarding, Compliance & Content Configuration
Operator installs the app, gets through first-run registration and the compliance gate, and configures the entire distribution business: uploads the image library and sets up every receiver (contact, channel, per-receiver daily count range, schedule). After this epic, the app is fully configured and ready to run — nothing sends yet.
**FRs covered:** FR1, FR2, FR6, FR7
**Implementation notes (from party-mode review):** the first story in this epic should stand up the shared networking/config foundation (Retrofit client setup, `AppConfig.kt`) generically — not scoped narrowly to the compliance API — since Epic 2 extends the same foundation for WhatsApp Business API and SMTP. The compliance non-compliant halt screen needs a real (if minimal) unhappy-path design, not just a bare message — its story should specify what the operator actually sees and can do from that screen.

### Epic 2: Automated Daily Distribution
The operator's daily manual sending task now happens on its own: each scheduled send picks a randomized, non-repeating image set per receiver and delivers it over WhatsApp/email, recovering automatically from offline gaps without ever backfilling stale days. This is the core value of the whole app — replacing the manual work.
**FRs covered:** FR3, FR4

### Epic 3: Delivery Proof & Data Housekeeping
Operator can look back at least 30 days per receiver to resolve a "did I get my images" dispute, and old transmission history cleans itself up automatically on a configurable schedule instead of growing forever.
**FRs covered:** FR5, FR8

## Epic 1: Setup — Onboarding, Compliance & Content Configuration

Operator installs the app, gets through first-run registration and the compliance gate, and configures the entire distribution business: uploads the image library and sets up every receiver. After this epic, the app is fully configured and ready to run — nothing sends yet. First story stands up the shared networking/config foundation (`AppConfig.kt`, Retrofit client) generically, since Epic 2 extends it for WhatsApp/SMTP.

### Story 1.1: First-Run Registration & Compliance Gate

As the person setting up the app (the "customer"),
I want to enter my name and city once on first launch and have the app register my install and confirm I'm compliant,
So that the app is authorized to run for me.

**Acceptance Criteria:**

**Given** the app is launched for the first time
**When** I enter my first name/nickname and city and submit
**Then** those values are saved and locked with no edit path afterward
**And** the app POSTs {first name/nickname, city} to the admin's registration endpoint

**Given** registration has completed
**When** the app performs its compliance check
**Then** it calls the hardcoded compliance API live (no auth) with {first name/nickname, city}
**And** if the response is compliant (or the call fails/times out), I proceed into the main app
**And** if the response is explicitly non-compliant, I see a "not compliant — contact admin" screen with no path to any other app function
**And** a failed/unreachable compliance check never blocks me — only an explicit non-compliant response does

### Story 1.2: Image Library Management

As the operator,
I want to upload images and mark each one active or inactive,
So that I control exactly what can be sent.

**Acceptance Criteria:**

**Given** I'm on the image library screen
**When** I upload one or more images
**Then** they are copied into app-private internal storage and listed with an active/inactive toggle

**Given** an image is listed
**When** I toggle it to inactive
**Then** it is immediately excluded from future random selection
**And** toggling it back to active makes it eligible again
**And** an image already selected/queued for a send before being toggled inactive is unaffected — it still sends

### Story 1.3: Receiver Configuration

As the operator,
I want to add and manage receivers with their contact details, delivery channel, per-receiver image-count range, and one or more daily schedule times,
So that each receiver gets exactly the distribution I've set up for them.

**Acceptance Criteria:**

**Given** I'm on the receivers screen
**When** I add a new receiver with a name, a +91 phone number (for WhatsApp) or an email address (for email), a min/max daily image count, and one or more daily schedule times (minimum 4)
**Then** the receiver is saved and appears in my receiver list

**Given** a receiver exists
**When** I edit or remove it
**Then** the change is saved and takes effect from the next scheduled send onward
**And** changing one receiver's settings never affects another receiver's schedule, channel, or count range

## Epic 2: Automated Daily Distribution

The operator's daily manual sending task now happens on its own: each scheduled send picks a randomized, non-repeating image set per receiver and delivers it over WhatsApp/email, recovering automatically from offline gaps without ever backfilling stale days.

### Story 2.1: Randomized Daily Selection

As the operator,
I want each receiver to automatically get a randomized, non-repeating set of images at their scheduled time,
So that my daily manual sending job is replaced without receivers noticing obvious repeats.

**Acceptance Criteria:**

**Given** a receiver's scheduled send time has arrived
**When** the app selects images for that receiver
**Then** it picks a random count between that receiver's configured min and max
**And** it selects that many currently-active images, excluding any image sent to that receiver in the last 7 days

**Given** the 7-day-exclusion pool is smaller than the needed count
**When** the app can't reach the needed count without repeating
**Then** it falls back to allowing repeats until the count is reached

**Given** the total active image count is itself smaller than the needed count
**When** the app selects images
**Then** it sends only the available active images — no duplicates, no topping up

**Technical note:** this story introduces the `Transmission` entity/DAO (needed to query the 7-day exclusion window); Story 2.2 extends it by writing rows as sends are attempted. This is a normal build-on-previous-story sequence, not a forward dependency.

### Story 2.2: Offline-Safe Queued Delivery

As the operator,
I want selected images queued and delivered automatically over WhatsApp or email, retrying through temporary outages,
So that a bad network moment doesn't mean a missed day for a customer.

**Acceptance Criteria:**

**Given** images have been selected for a receiver's scheduled send
**When** the app attempts delivery
**Then** it queues by image ID (not image data) and sends via the receiver's configured channel (WhatsApp Business API or Gmail SMTP)

**Given** a queued send fails
**When** the app retries
**Then** it retries the same queued item up to 3 times regardless of failure reason

**Given** the device was offline through an entire scheduled window
**When** connectivity returns
**Then** only that day's missed batch is resumed — earlier missed days are not backfilled

## Epic 3: Delivery Proof & Data Housekeeping

Operator can look back at least 30 days per receiver to resolve a "did I get my images" dispute, and old transmission history cleans itself up automatically on a configurable schedule instead of growing forever.

### Story 3.1: Delivery Dashboard

As the operator,
I want to see, per receiver, what was sent and when, going back at least 30 days,
So that I can resolve a "I didn't get my images" dispute.

**Acceptance Criteria:**

**Given** transmissions have occurred
**When** I open the dashboard for a receiver
**Then** I see a timestamped list of every image sent to them within the retention window
**And** I can switch to a different receiver to see their own history independently

### Story 3.2: Configurable Retention & Purge

As the operator,
I want old transmission history to clean itself up automatically after a retention period I control,
So that the database doesn't grow forever and I'm not stuck with a fixed window.

**Acceptance Criteria:**

**Given** a retention period is set (default 30 days)
**When** transmission records exceed that age
**Then** they are purged automatically on a recurring basis

**Given** I change the retention period
**When** the new value is saved
**Then** it governs future purges only — already-deleted rows are not retroactively affected
