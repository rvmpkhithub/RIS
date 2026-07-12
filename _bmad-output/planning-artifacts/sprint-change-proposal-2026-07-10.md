# Sprint Change Proposal — Multi-Schedule Receivers

Date: 2026-07-10
Trigger story: 1-3-receiver-configuration (status `done` at time of trigger)

## 1. Issue Summary

Story 1.3 (Receiver Configuration) shipped with exactly one schedule time per receiver (`Receiver.scheduleTime: Int`, minutes-since-midnight), matching the original SPEC.md/epics.md wording ("a daily send schedule"). After the story was reviewed and marked `done`, the operator (user) identified that the real business sends images **at least 4 times a day**, not once — the one-schedule model doesn't match how the friend's photo-subscription business actually operates.

Two design questions were resolved with the user before drafting changes:

1. **Schedule count**: variable per receiver, minimum 4 (not a fixed count of exactly 4).
2. **Count-per-send model**: while reviewing `mechanics.md`, it became clear the *existing* selection algorithm already specifies an independent random count roll "at each scheduled send" — this was actually already written generically enough to support N sends/day. The user confirmed: keep that model (each schedule independently rolls within the receiver's min/max) rather than inventing a new "shared daily total split across sends" algorithm.

This second discovery significantly narrowed the blast radius: `mechanics.md`'s CAP-3 algorithm needs **no changes at all**, and Epic 2/3 story ACs (2.1, 2.2, 3.1) already read generically enough to require no changes either.

## 2. Impact Analysis

**Epic Impact:**
- Epic 1 (Setup & Configuration) — Story 1.3 requires rework: data model, repository, and UI all assumed a single schedule.
- Epic 2 (Automated Daily Distribution) — no impact. Story 2.1's AC ("a receiver's scheduled send time has arrived") and Story 2.2's AC already work correctly for a receiver with N schedule times; each is just a distinct trigger event. Not yet implemented, so no rework needed, just confirmed compatible.
- Epic 3 (Delivery Proof & Housekeeping) — no impact. Story 3.1's dashboard already shows "a timestamped list of every image sent," which naturally handles multiple sends/day per receiver via distinct `Transmission` rows.

**Artifact Conflicts (resolved in this proposal, already applied):**
- `SPEC.md` — CAP-2 intent/success bullets updated (schedule → schedule times, minimum 4).
- `mechanics.md` — no change (already generic).
- `ARCHITECTURE-SPINE.md` — AD-12 (SendWorker rule), Consistency Conventions table, and ER diagram updated to introduce a `ReceiverSchedule` entity (one-to-many from `Receiver`) replacing the single `Receiver.scheduleTime` field.
- `DESIGN.md` — receiver-row component spec updated (schedule count summary instead of a single time).
- `EXPERIENCE.md` — IA table and Component Patterns updated (schedule-time list add/remove, minimum-4 inline validation).
- `epics.md` — FR2 and Story 1.3's statement/AC1 updated.

**Technical Impact:**
- `Receiver.kt` loses `scheduleTime: Int`.
- New `ReceiverSchedule` entity + DAO (`receiverId` FK, `time: Int`).
- `AppDatabase` version 3→4, new `MIGRATION_3_4` (create `receiver_schedules` table; recreate `receivers` table without `scheduleTime` — SQLite `DROP COLUMN` requires the copy-and-rename pattern for broad compatibility).
- `ReceiverRepository`/`ReceiverRepositoryImpl` redesigned to load/save a receiver together with its schedule times (a `ReceiverWithSchedules` shape).
- `ReceiversViewModel`, `ReceiversScreen` (row display), `ReceiverEditScreen` (schedule-time list UI, add/remove, minimum-4 validation) all need rework.
- All Receiver-related tests need updating for the new shape.
- This affects a real device install if the friend's tablet already has this build — but per this project's status, no APK has been sent to the friend yet (still in the emulator-verification phase), so there is no real production data at risk. The migration is still written properly (per AD-15, no destructive fallback) since the pattern must be right before it ever ships.

## 3. Recommended Approach

**Selected: Option 1 — Direct Adjustment.** Reopen Story 1.3 rather than creating a new story or rolling back. This is a correction to Story 1.3's own delivered scope, not new functionality — fragmenting it into a separate story would scatter the "receiver configuration" concern across two story files for no benefit. No rollback needed (the surrounding app shell, Images tab, and compliance gate are all unaffected and stay as-is). No MVP scope reduction needed — this is an in-scope correction, not a cut.

- **Effort:** Medium (new entity + migration + repository redesign + UI rework, but contained to the Receivers vertical slice; no changes needed to Epic 2/3 planning).
- **Risk:** Low — no real user data exists yet on any real device; the emulator install can be wiped/reinstalled freely during rework.

## 4. Detailed Change Proposals

All planning-artifact edits listed in Section 2 have been applied (see each file's own diff for exact before/after). Summary:

| Artifact | Change |
|---|---|
| SPEC.md | CAP-2 intent/success: schedule → schedule times (min. 4) |
| mechanics.md | No change |
| ARCHITECTURE-SPINE.md | AD-12 rule text; Consistency Conventions table; ER diagram (`ReceiverSchedule` entity) |
| DESIGN.md | receiver-row: schedule count summary instead of single time |
| EXPERIENCE.md | IA table; new Component Pattern row for schedule-time list + min-4 validation |
| epics.md | FR2; Story 1.3 statement + AC1 |

## 5. Implementation Handoff

**Scope classification: Minor.** Directly implementable by the Developer agent (no PM/Architect strategic replan needed — this is a data-model and UI correction within an already-understood epic).

**Plan:**
1. Reopen `1-3-receiver-configuration.md`: revert Status to `in-progress`, append a new Task 7 covering the schedule-model rework (entity/migration/repository/UI/tests), keep existing Dev Agent Record/Change Log history intact (append, not overwrite).
2. Implement via the standard dev-story discipline: red-green-refactor, real migration, real tests (unit + instrumented), live emulator verification with precise tap coordinates.
3. Re-run `bmad-code-review` on the new changes before marking done again.
4. Update `sprint-status.yaml` accordingly (story stays at `in-progress` until rework is verified, then `review`, then `done`).

**Success criteria:** a receiver can be created/edited with 4+ schedule times (add/remove UI, minimum-4 enforced), persisted correctly across app restarts, with a clean v3→v4 migration verified live against the current on-device install, and all existing Receiver tests updated and passing.
