---
title: Reconciliation Review — ARCHITECTURE-SPINE.md vs SPEC.md + mechanics.md
date: 2026-07-09
scope: >
  Claim-by-claim check that every capability (CAP-1..CAP-8), constraint, non-goal,
  success signal, assumption in SPEC.md, and every rule/table row in mechanics.md,
  is respected by ARCHITECTURE-SPINE.md — either explicitly governed by an AD/
  convention, or at minimum not contradicted.
verdict: MINOR GAPS
---

## Known, deliberate divergence (not a gap)

**SPEC.md constraint** "App data persists in a local PostgreSQL database only" vs
**spine** "on-device SQLite, direct calls to WhatsApp Business Cloud API / Gmail
SMTP / a hardcoded compliance API, no server component" (scope line, header) and
`Room / SQLite` throughout (Design Paradigm, Stack table, Structural Seed diagram).

This is the deliberate divergence flagged by the task: the architecture
conversation decided SQLite via Room over Postgres (per the architecture memlog).
Everything else about the constraint — "no cloud database dependency" — is still
honored (SQLite is fully on-device). **Present and expected; not counted as a gap.
To be synced back to SPEC.md afterward.**

---

## CAP-1..CAP-8 walkthrough

| # | Claim | Verdict | Notes |
|---|---|---|---|
| CAP-1 | Upload library, flag active/inactive; inactive never selected; reactivating makes selectable again | PASS | `Image.active: bool`, `data/local` + `ui/images`, AD-5/AD-6. Reactivation is just a boolean flip on the same field — no contradiction, though the "reactivating" success clause isn't independently called out (fine, it's a natural consequence of a boolean flag). |
| CAP-2 | Up to ~10 receivers, each with name, +91 mobile number, channel, per-receiver min/max, per-receiver schedule; changing one doesn't affect another | PASS (with a note) | `Receiver` entity has `name, channel, phoneOrEmail, minCount, maxCount, scheduleTime` — all independently configurable per row (relational model naturally isolates receivers). **Note:** SPEC.md's CAP-2 intent lists "a +91 mobile number" as a receiver field independent of channel, but doesn't separately list an email address field. The spine collapses this into a single `phoneOrEmail` string keyed off `channel`. This is a reasonable resolution of an ambiguity that already existed in SPEC.md (not a spine-introduced contradiction), but it means a receiver's phone number is not retained when `channel=EMAIL`. Worth a one-line confirmation with the requester when SPEC.md is synced, not a structural gap. |
| CAP-3 | Random count Z in [min,max], select active pool, exclude last-7-days-sent, fallback rules per mechanics.md | PASS — explicitly governed | Capability map row explicitly cites `AD-10, mechanics.md`. AD-7 explicitly names "7-day window" in its list of date-math concerns. `domain/ImageSelectionEngine` is named as the sole implementation site (AD-10). This is the best-covered capability in the spine. |
| CAP-4 | Queue by image ID (not bytes); failed sends retry up to 3x; resume on reconnect; offline through whole window → only that day sends, no backfill | **GAP (quiet numbers/rules not bridged)** | See "Cross-cutting gap" section below — the `TRANSMISSION.attemptCount` field supports tracking retries, but nowhere does the spine state the cap is 3, nor mention the no-backfill rule. Unlike CAP-3's row, CAP-4's Capability Map row (`worker/SendWorker, data/repository | AD-4, AD-5, AD-8`) does **not** cite `mechanics.md`, so there's no explicit bridge sending an implementer back to the source of truth for these numbers. Not contradicted — just not explicitly governed. |
| CAP-5 | Per-receiver log, ≥30 days, timestamp per image sent | PASS — explicitly governed | AD-7 explicitly names "30-day dashboard" in its date-math rationale; `ui/dashboard` + `Transmission` entity (`sentAt`, `status`) carries the needed data; AD-6/AD-7 cited in the map. |
| CAP-6 | First launch: collect nickname + city, **lock permanently (no edit path)**, POST to admin registration endpoint | **GAP (quiet rule not bridged)** | `ComplianceState` entity has `nickname, city` fields and CAP-6 maps to `ui/setup`, `data/remote` under AD-2/AD-3 (endpoint centralization, single egress point) — both correctly cover the *registration call* mechanics. But "lock permanently — no edit path exists after this point" is a UI/domain invariant never stated as a rule anywhere in the spine (no AD, no convention-table entry, no "Prevents" clause). Nothing contradicts it, but nothing would stop a future `ui/setup` change from adding an edit affordance either — this is exactly the kind of one-way, no-UI-path rule that AD-9 handles for the *update mechanism* but that has no equivalent for registration lock. |
| CAP-7 | Hardcoded compliance API check on nickname+city; halt only on explicit non-compliant; everything else fail-open | PASS — explicitly governed | AD-10 explicitly names "ComplianceGate (CAP-7 fail-open rule)" as the sole implementation site. AD-2/AD-3 cover the hardcoded, centralized, single-client endpoint. This is the second-best-covered capability. |
| CAP-8 | Purge transmission history older than configurable retention window, default 30 days | PASS (with a note) | `RetentionSetting.retentionDays` is modeled as configurable; `domain/RetentionPolicy` is the sole rule site (AD-10). **Note:** the specific *default value* of 30 days is never stated in the spine (AD-7's "30-day dashboard" phrase refers to CAP-5's display window, not CAP-8's default retention value — mechanics.md says these two 30s happen to match, but they're conceptually distinct settings). This is a config/seed-data detail more than an architectural invariant, so it's a minor omission rather than a structural gap — but worth setting explicitly in `AppConfig.kt` or DB seed at implementation time so it doesn't drift from mechanics.md. |

---

## Constraints (SPEC.md)

| Constraint | Verdict | Notes |
|---|---|---|
| Local PostgreSQL only, no cloud DB | NOTED DIVERGENCE | See top of document — SQLite via Room, deliberate, expected. |
| Sideloaded distribution only; no store/update mechanism | PASS — explicit | AD-9 "Manual updates, no in-app update path", Deployment section: "manual APK transfer... no CI/CD pipeline." |
| Receiver numbers assume fixed +91 | **GAP (quiet, minor)** | Never mentioned in the spine. `Receiver.phoneOrEmail` is an untyped string with no country-code handling, validation, or formatting convention noted. Not contradicted (there's no per-receiver country-code field, which is at least consistent with "+91 fixed, not selectable"), but there's no explicit statement of where/how the +91 assumption is enforced (e.g., a stored E.164 format, a UI-level prefix, or purely a WhatsApp-API-time concern). Low-risk gap — likely resolved at implementation time in `ui/receivers` input validation — but currently unstated. |
| Channels limited to WhatsApp Business Cloud API + Gmail SMTP, no others | PASS — explicitly governed | AD-2 names exactly these two (plus Compliance API) as the only three egress points, one Retrofit/client class each. Convention table restricts `channel` enum to `WHATSAPP`\|`EMAIL`. Matches the SPEC constraint precisely. |
| Compliance API hardcoded, not user-configurable; no auth on compliance/registration calls | PASS — explicitly governed | AD-3 (centralized, hardcoded, single-file config) + Consistency Conventions table: "Auth: none on compliance/registration APIs (per spec)". Explicit, verbatim acknowledgement. |
| Only first name/nickname collected, never full legal name; not treated as dedicated-PII | PASS | Entity field is literally named `nickname` (not `name`/`legalName`), consistent terminology. No dedicated PII-handling machinery added anywhere (encryption-at-rest, consent flows, etc.) — consistent with "deliberately minimal, not PII-grade" framing. Not contradicted. |
| Queued item still sends even if later flagged inactive before queue fires | **GAP (quiet rule not bridged)** — same root cause as CAP-4 | Not stated anywhere in the spine. `SendWorker` (CAP-4) and `ImageSelectionEngine` (CAP-3) are cleanly separated per AD-10's "business rules live only in domain/", which is good structurally, but nothing in the spine tells an implementer that `SendWorker` must NOT re-check `Image.active` before firing an already-queued transmission. Absence-of-a-check is exactly the kind of rule a layered/AD spine silently drops. Not contradicted — just not explicit. |
| Fail-open by design: only explicit non-compliant halts; every other failure mode degrades gracefully | PASS — explicitly governed | AD-10 names this exactly: "ComplianceGate (CAP-7 fail-open rule)". This is the constraint best captured verbatim in the spine. |
| Failed transmissions retry a maximum of 3 times | **GAP (quiet number not bridged)** | Confirmed by direct text search of the spine: no occurrence of "3", "retries" cap, or any retry-count policy. `TRANSMISSION.attemptCount: int` is present and *could* carry this, but the cap value and where it's enforced (Worker retry policy? `SendWorker`'s own loop? WorkManager's built-in backoff policy?) is never stated. This is the most concrete instance of the numeric-detail gap the task asked me to hunt for. |

---

## Non-goals (SPEC.md)

| Non-goal | Verdict | Notes |
|---|---|---|
| Payment/billing between operator and receivers | PASS | Nothing in the spine models payment; correctly absent. |
| In-app admin interface for compliance status | PASS | `ui/` structure lists only setup, images, receivers, dashboard, compliance (status display, presumably read-only) — no admin/compliance-editing screen. Not contradicted. |
| Generating/editing/curating images | PASS | `ui/images` implied to be upload + active/inactive flag only, consistent with "distribute only, don't create/edit". Not contradicted. |
| Store distribution / in-app update mechanism | PASS — explicit | Covered by AD-9 (see above), doubly reinforced in Deployment & environments section. |

---

## Mechanics.md failure-mode table — cross-check

| Scenario (mechanics.md) | Spine coverage | Verdict |
|---|---|---|
| Compliance API returns explicit non-compliant → halt | AD-10 ComplianceGate | PASS |
| Compliance API unreachable/timeout → don't halt, retry at next scheduled check | AD-10 (fail-open) + AD-4 (`ComplianceCheckWorker` as periodic WorkManager job, naturally re-checks on next schedule) | PASS |
| Tablet offline through entire window → resume only that day, no backfill | Not stated anywhere | **GAP** — same root cause as the CAP-4/retry gap below |
| Active pool smaller than Z → send only available, no duplicate/top-up | Delegated via `ImageSelectionEngine` + explicit `mechanics.md` citation in CAP-3's map row | PASS |
| 7-day pool exhausted → allow repeats, capped at active count | Same as above | PASS |
| Image inactive after queued, before fire → still sent | Not stated anywhere | **GAP** — flagged above under constraints |
| Transmission fails (any reason) → retry up to 3x | Not stated anywhere (only `attemptCount` field exists, no cap value) | **GAP** — flagged above under constraints |

**Root-cause observation:** all three CAP-4-shaped gaps (3-retry cap, no-backfill, inactive-still-sends) share one cause — CAP-3's Capability Map row explicitly cites `mechanics.md` as a companion reference (`AD-10, mechanics.md`), which sends any implementer back to the source of truth for the *exact* numbers and edge cases. CAP-4's row (`AD-4, AD-5, AD-8`) does **not** cite `mechanics.md`, so its quiet numeric/behavioral rules have no explicit bridge into the spine at all. This is a one-line fix (add `, mechanics.md` to CAP-4's Capability Map row, matching CAP-3's pattern), not a structural redesign.

---

## Assumptions (SPEC.md)

| Assumption | Verdict | Notes |
|---|---|---|
| Target platform is Android | PASS — explicit | Entire stack (Kotlin, Compose, Room, WorkManager, AGP) is Android-native. |
| +91 fixed/hardcoded for all receivers, not per-receiver selectable | PASS (soft) | `Receiver` entity has no country-code field, which is at least consistent with "fixed, not selectable" (there's nothing to select from). Combined with the constraint-level gap above (no stated enforcement point for +91), this is low-risk but still worth an explicit line at implementation time. |

---

## Success signal (SPEC.md)

No new claims beyond CAP-1..CAP-8 and the constraints above; all constituent pieces already checked. PASS by composition.

---

## Overall verdict: MINOR GAPS

Nothing in the spine **contradicts** SPEC.md or mechanics.md. The two best-governed
capabilities (CAP-3, CAP-7) show what full coverage looks like: an explicit AD
naming the rule by name, plus a direct citation of `mechanics.md` in the Capability
Map. The gaps below are all instances of the *same* pattern — quiet, specific
numbers/behaviors from mechanics.md that don't get the same explicit bridge:

1. **CAP-4 / retry cap of 3** — `attemptCount` field exists but the cap value and enforcement point are never stated in the spine.
2. **CAP-4 / no-backfill-of-missed-days rule** — never stated.
3. **CAP-4 (constraints) / "queued item still sends even if later flagged inactive"** — never stated; relies on an implementer *not* adding a check that isn't there.
4. **CAP-6 / "lock permanently, no edit path"** — registration call mechanics (AD-2/AD-3) are covered, but the one-way lock itself has no AD or convention-table entry.
5. **CAP-8 / default retention value of 30 days** — modeled as configurable, but the default number itself is unstated (distinct from AD-7's "30-day dashboard," which is CAP-5's window, not CAP-8's).
6. **+91 country-code enforcement point** — consistent by omission, but no explicit statement of where it's enforced.

**Suggested minimal fix:** add `, mechanics.md` to CAP-4's and CAP-6's Capability Map
rows (mirroring CAP-3/CAP-7's pattern), and add one short AD or convention-table
line each for: the 3-retry cap + no-backfill rule (could ride alongside AD-4), the
registration-lock invariant (could ride alongside AD-9's "no edit/update path"
theme), and the CAP-8 default-retention value (could live in AD-3's config
centralization). None of this requires restructuring the spine — it's additive.
