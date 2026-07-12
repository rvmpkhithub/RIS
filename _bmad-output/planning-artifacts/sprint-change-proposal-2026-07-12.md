# Sprint Change Proposal — Master Schedule Fallback

**Date:** 2026-07-12
**Requested by:** RIS
**Scope classification:** Moderate

## 1. Issue Summary

This is a new requirement, not a defect. Story 1.3 (Receiver Configuration) shipped with a per-receiver schedule that is **mandatory** — every receiver must have at least 4 daily schedule times, enforced at save time with no way to skip it.

The operator now wants:
1. A single **master schedule** (minimum 4 times), configured once in Settings.
2. The **per-receiver schedule to become optional** — if a receiver is given a schedule, the existing minimum-4 rule still applies; if not, the receiver has none.
3. At dispatch time, a receiver with **no schedule of its own falls back to the master schedule**.

This closes a real operator friction point: today, adding a new receiver requires configuring 4+ times for that receiver specifically, even when most receivers would happily share one common daily schedule.

## 2. Impact Analysis

### Epic impact

- **Epic 1 / Story 1.3 (done)** — AC2 required "one or more daily schedule times (minimum 4)" unconditionally. Relaxed to: schedule is optional; if any are given, minimum 4 still applies. A new AC was added stating the master-schedule fallback (cross-referencing the new Story 2.3).
- **Epic 2 (in-progress again)** — new **Story 2.3: Master Schedule Fallback** added, covering the Settings-side master schedule configuration and the `SendDispatcher` fallback logic. Epic 2's own status reverted from `done` to `in-progress` since it now has a backlog story.
- **Epic 3 / Story 3.2 (done)** — no AC changes; the master schedule's UI is a new sibling section on the Settings screen Story 3.2 already built. No epic status change needed here — Epic 3's own stories are unaffected in scope.
- No epic becomes obsolete; no resequencing needed beyond Epic 2 reopening.

### Artifact conflicts found and resolved

| Artifact | Conflict | Resolution |
|---|---|---|
| `SPEC.md` | CAP-2 assumed mandatory per-receiver schedule; no capability existed for a master schedule | CAP-2 wording relaxed; new **CAP-9** added |
| `mechanics.md` | No fallback rule existed anywhere | New "Master schedule and fallback (CAP-9)" section added; Failure-mode table gained a row |
| `epics.md` | Story 1.3's AC2 was unconditional | Story 1.3 AC2 relaxed + new AC added; new **Story 2.3** added to Epic 2 |
| `ARCHITECTURE-SPINE.md` | No entity for a master schedule; AD-12's dispatch rule assumed mandatory schedule; Consistency Conventions table stated schedule mandatoriness as fact | New `MASTER_SCHEDULE` entity in the ERD; new **AD-16**; AD-12 rule text updated; Consistency Conventions and Capability→Architecture Map tables updated |
| `DESIGN.md` | `receiver-row` component assumed a schedule always exists; no token/component existed for a master schedule editor | `receiver-row` text updated ("Uses master schedule" case); new `master-schedule-list` token + Components entry (explicitly reuses the existing schedule-list pattern, not a new one) |
| `EXPERIENCE.md` | IA table, Component Patterns, and State Patterns all assumed mandatory per-receiver schedule; Settings' IA entry didn't mention the master schedule | IA table rows updated (Receiver Edit, Settings); "Schedule time list" component pattern rewritten for the optional-but-min-4-if-any rule and its Settings reuse; new State Patterns row for the partially-filled case |

No conflicts found in deployment/CI/testing-strategy artifacts (none of substance exist for this project beyond what's already covered above).

## 3. Recommended Approach

**Selected: Direct Adjustment (Option 1).**

- Modify Story 1.3's AC (already done in `epics.md`) and its implementation (`ReceiverEditScreen`'s validation logic) in place — no rollback of Story 1.3's shipped code required; the schema (`ReceiverSchedule`) doesn't change shape, only its cardinality constraint relaxes from "≥1" to "≥0".
- Add one new story (2.3) within the existing epic structure for the master schedule itself and the dispatch fallback.
- No PRD/MVP scope reduction — this is a scope *addition* that makes an already-shipped capability more flexible, not a cut.

**Rejected alternatives:**
- *Rollback* (Option 2): no shipped code needs reverting — the change is additive (new table, new fallback branch) plus one relaxed validation rule. Rolling back Story 1.3 or 2.2 would be pure busywork.
- *MVP scope review* (Option 3): not applicable — nothing about this change threatens the MVP's core goals; if anything it strengthens the "zero manual effort" goal in `SPEC.md`'s own success narrative.

**Effort:** Medium. **Risk:** Low-Medium — the riskiest part is getting `SendDispatcher`'s fallback branch and the schema migration right; the UI-side change (schedule list reused verbatim) and validation relaxation are both small, well-contained edits.

## 4. Detailed Change Proposals

All of the following have already been applied to their respective documents (approved incrementally during this session):

1. **`SPEC.md`** — CAP-2 relaxed; new CAP-9 added.
2. **`mechanics.md`** — new "Master schedule and fallback (CAP-9)" section; Failure-mode table row added.
3. **`epics.md`** — Story 1.3 AC2 relaxed, new AC added; new Story 2.3 added to Epic 2.
4. **`ARCHITECTURE-SPINE.md`** — new `MASTER_SCHEDULE` ERD entity; new AD-16; AD-12 rule text updated; Consistency Conventions and Capability→Architecture Map tables updated.
5. **`DESIGN.md`** — `receiver-row` updated; new `master-schedule-list` token + Components entry.
6. **`EXPERIENCE.md`** — IA table, Component Patterns, and State Patterns tables updated.

(Full before/after diffs for each are preserved in this session's conversation history; not re-duplicated here to keep this proposal from drifting out of sync with the documents themselves.)

## 5. Implementation Handoff

**Scope: Moderate** — requires backlog reorganization (done: `sprint-status.yaml` updated) and coordinated developer work across two touch points that must land together (a receiver with no schedule is only safe to allow once the fallback exists).

**Handoff to: Developer agent**, via the standard `create-story` → `dev-story` → `code-review` cadence, starting with **Story 2.3: Master Schedule Fallback** (currently `backlog` in `sprint-status.yaml`).

**What Story 2.3's implementation must cover** (already captured in its epics.md entry and the architecture updates above, restated here for a clean handoff):
- New `MasterSchedule` Room entity (`{id, time}`, no FK) + DAO + migration, seeded with 4 default times at creation (mirrors `RetentionSetting`'s seed-at-migration pattern, per AD-16's "at least 4 rows always exist here").
- New Settings UI section reusing the existing "Schedule time list" component verbatim (same add/remove rows, same time picker, same inline-error pattern) — per DESIGN.md/EXPERIENCE.md above.
- **`ReceiverEditScreen`'s validation relaxed**: currently blocks save whenever `scheduleTimes.size < 4`, including zero. Must change to: block only when `scheduleTimes.isNotEmpty() && scheduleTimes.size < 4` — zero is valid. *(This is technically an amendment to Story 1.3's shipped code, but it's tightly coupled to Story 2.3's own work and should land in the same story rather than as a separate reopened Story 1.3 pass.)*
- **`SendDispatcher.dispatchDueSlots`'s fallback**: currently iterates `receiverWithSchedules.scheduleTimes` directly. Must check that list first; if empty, use the master schedule's times for that receiver's dispatch check instead (queried live, never copied onto the receiver — per AD-16).
- Receiver row summary text (Receivers screen) updated to show "Uses master schedule" when a receiver has none of its own, per the `DESIGN.md` update above.

**Dependencies/sequencing:** none blocking — Story 2.3 can start immediately; no other in-flight work touches these files.

## 6. Sprint Status Changes Applied

```diff
- epic-2: done
+ epic-2: in-progress
  2-1-randomized-daily-selection: done
  2-2-offline-safe-queued-delivery: done
+ 2-3-master-schedule-fallback: backlog
  epic-2-retrospective: optional
```
