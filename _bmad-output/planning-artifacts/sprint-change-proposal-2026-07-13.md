# Sprint Change Proposal — Single-Image Selection & Image Tagging

**Date:** 2026-07-13
**Requested by:** RIS
**Scope classification:** Moderate

## 1. Issue Summary

This is a new requirement, not a defect. The app currently ships two related but separate mechanics that this change simplifies and extends:

1. **Batch image selection.** At each scheduled send, `ImageSelectionEngine` picks a random count Z between a receiver's configured min/max images, then selects that many active images. The operator wants this replaced with a fixed count of exactly **one image per scheduled send** — removing the min/max range entirely, since a receiver's daily image total is now simply a function of how many schedule times it has.
2. **Untagged, thumbnail-only image library.** Images currently have no metadata beyond a file path and an active flag, and the Image Library screen shows a grid of thumbnails. The operator wants each image taggable with an optional **title and short description**, and the library browsed as a **titled list with a "View" button** instead of thumbnails.

Both changes were requested together in one message ("update all bmad documents: 1. Image selection one at a time, 2. Add ability to add title and short description to the image, 3. Change the Images page to a titled list with a View button, no thumbnails").

Two points of ambiguity were resolved with the operator before drafting proposals:
- "One at a time" was confirmed to mean **always exactly 1 image per scheduled send**, not a batch selected sequentially — this makes the receiver's Min/Max images fields obsolete.
- Tagging entry point and the "View" action defaulted to **a dedicated in-app full-screen detail view** (no response was received to the clarifying question in time, so the lower-risk, most architecturally-consistent option was chosen and flagged as an assumption — an external ACTION_VIEW intent would need a `FileProvider`, which this app has deliberately avoided per AD-14's app-private-storage-only rule).
- Confirmed explicitly with the operator: the image active/inactive flag is **not** being removed — it's untouched by this change, just relocated from the grid into the new list row / detail view.

## 2. Impact Analysis

### Epic impact

- **Epic 1 / Story 1.2 (done)** — no AC changes to Story 1.2 itself (its shipped active/inactive toggle behavior is unchanged); a new **Story 1.4: Image Tagging & List View** added to revisit its shipped `Image` entity and Image Library screen.
- **Epic 1 / Story 1.3 (done)** — AC1's min/max mention removed; AC2's "count range" reference removed. Epic 1's own status reverted from `done` to `in-progress` since it now has a backlog story (1.4).
- **Epic 2 / Story 2.1 (done)** — ACs rewritten for single-image selection (no more random count Z); a new **Story 2.4: Single-Image Selection** added to revisit its shipped `ImageSelectionEngine` and Story 1.3's shipped `Receiver` entity/form together. Epic 2 was already `in-progress` (Story 2.3 still in `review`), so no epic-status change needed beyond adding the new backlog story.
- **Epic 3** — untouched, no changes.
- No epic becomes obsolete; no resequencing needed beyond the two backlog-story additions.

### Artifact conflicts found and resolved

| Artifact | Conflict | Resolution |
|---|---|---|
| `SPEC.md` | CAP-1 didn't mention tagging; CAP-2 assumed a mandatory min/max count; CAP-3 described a random-batch-count algorithm | CAP-1 gains tagging language; CAP-2's count clause removed; CAP-3 rewritten for exactly-one-image selection |
| `mechanics.md` | Selection algorithm (CAP-3) was written entirely around a variable count Z; Failure-mode table had a "pool smaller than Z" row | Algorithm rewritten to a 4-step single-image version; failure-mode row replaced with "zero active images available" |
| `epics.md` | FR1/FR2/FR3 referenced tagging-less images and a mandatory count range; Story 1.3's AC/user-story mentioned min/max; Story 2.1's ACs described the batch algorithm; no story existed for tagging/list-view or for the count-removal implementation | FR1/FR2/FR3 text updated; Story 1.3 amended in place (count references removed); Story 2.1 ACs rewritten; new **Story 1.4** added to Epic 1; new **Story 2.4** added to Epic 2 |
| `ARCHITECTURE-SPINE.md` | ERD's `RECEIVER` entity had `minCount`/`maxCount`; `IMAGE` entity had no metadata fields | ERD updated: `RECEIVER.minCount`/`maxCount` removed; `IMAGE.title`/`description` (nullable) added. No new AD needed — governed by existing AD-10/AD-15 |
| `DESIGN.md` | `image-grid-item` component assumed a thumbnail grid | Replaced with `image-list-item` (title + View button + toggle, no thumbnail) and a new `image-detail-view` component |
| `EXPERIENCE.md` | IA table, Component Patterns, and Accessibility Floor all assumed a thumbnail grid with no detail screen | IA table gained an "Image Detail" row; Component Patterns' "Image grid item" row replaced with "Image list item" + "Image detail view" rows; Accessibility Floor's tap-target note updated |

No conflicts found in deployment/CI/testing-strategy artifacts (none of substance exist for this project beyond what's already covered above).

## 3. Recommended Approach

**Selected: Direct Adjustment (Option 1).**

- Amend Story 1.3's and Story 2.1's ACs in place (already done in `epics.md`) — their shipped code doesn't need reverting, only extending/simplifying via two new stories.
- Add two new stories (1.4, 2.4) within the existing epic structure, each explicitly scoped to revisit a specific piece of already-shipped code, mirroring the precedent set by Story 2.3 (master schedule fallback) amending Story 1.3.
- No PRD/MVP scope reduction — CAP-1 gains a feature (tagging), CAP-2/CAP-3 simplify (removing a range the operator no longer wants), and the MVP's core "zero manual effort" goal is unaffected either way.

**Rejected alternatives:**
- *Rollback* (Option 2): no shipped code needs reverting — Story 1.2's active/inactive toggle, Story 1.3's contact/channel/schedule fields, and Story 2.1's 7-day-exclusion/repeat-fallback logic are all still correct and reused as-is; only the count dimension and the image metadata/UI are changing.
- *MVP scope review* (Option 3): not applicable — nothing here threatens the MVP's core goals; tagging is a small operator-facing addition, and single-image selection is a simplification, not a cut.

**Effort:** Medium. **Risk:** Low-Medium — the riskiest part is the `Receiver` schema migration dropping `minCount`/`maxCount` (a destructive-but-intentional recreate-table migration, same pattern as `MIGRATION_3_4`); the `Image` metadata addition is a simple additive migration, and the UI change (grid → list) is a straightforward Compose refactor with a well-established precedent in this codebase (`ScheduleTimeListEditor`'s recent extraction into a shared component shows the same kind of surgical UI-layer change working cleanly).

## 4. Detailed Change Proposals

All of the following have already been applied to their respective documents (approved incrementally during this session):

1. **`SPEC.md`** — CAP-1 gains tagging language; CAP-2's min/max count clause removed; CAP-3 rewritten for single-image selection.
2. **`mechanics.md`** — Selection algorithm (CAP-3) rewritten from a 5-step random-count algorithm to a 4-step single-image algorithm; Failure-mode table's "pool smaller than Z" row replaced with "zero active images available."
3. **`epics.md`** — FR1/FR2/FR3 updated; Story 1.3's user-story/AC1/AC2 amended (count references removed); Story 2.1's ACs rewritten for single-image selection; new Story 1.4 (Image Tagging & List View) added to Epic 1; new Story 2.4 (Single-Image Selection) added to Epic 2.
4. **`ARCHITECTURE-SPINE.md`** — ERD: `RECEIVER.minCount`/`maxCount` removed; `IMAGE.title`/`description` (nullable) added.
5. **`DESIGN.md`** — `image-grid-item` replaced with `image-list-item`; new `image-detail-view` component; Components body section updated to match.
6. **`EXPERIENCE.md`** — IA table gained an "Image Detail" row; Component Patterns' grid-item row replaced with list-item + detail-view rows; Accessibility Floor's tap-target note updated.

(Full before/after diffs for each are preserved in this session's conversation history; not re-duplicated here to keep this proposal from drifting out of sync with the documents themselves.)

## 5. Implementation Handoff

**Scope: Moderate** — requires backlog reorganization (done: `sprint-status.yaml` updated) and coordinated developer work across two independent-but-related new stories.

**Handoff to: Developer agent**, via the standard `create-story` → `dev-story` → `code-review` cadence. Both new stories are currently `backlog` in `sprint-status.yaml`; the standard "first backlog story in file order" discovery will pick up **Story 1.4** first, then **Story 2.4** — no required ordering between them (they touch disjoint code: `Image`/Image Library vs. `Receiver`/`ImageSelectionEngine`), but 1.4 sits earlier in the file so it will be picked up first by default.

**What Story 1.4's implementation must cover** (restated from its epics.md entry for a clean handoff):
- `Image` entity gains nullable `title: String?`/`description: String?` columns (additive migration — no data loss, no recreate-table needed).
- New Image Detail screen: full-bleed image (loaded from app-private storage, same `ImageFileStore` path resolution already established), editable title/description fields, the active/inactive toggle, back navigation.
- Image Library screen's grid replaced with a list: each row shows title (or "Untitled") + a "View" button (navigates to Image Detail) + the active/inactive toggle (same one-tap behavior as today, just relocated).

**What Story 2.4's implementation must cover:**
- `Receiver.minCount`/`maxCount` dropped entirely — a destructive recreate-table migration (mirroring `MIGRATION_3_4`'s copy-and-rename pattern), since SQLite has no reliable cross-version `DROP COLUMN`.
- `ImageSelectionEngine`'s algorithm simplified to the 4-step single-image version from `mechanics.md`: build the 7-day-exclusion eligible pool, pick 1 if available, fall back to a repeat from all active images if the pool is empty, send nothing if there are zero active images at all.
- Receiver Add/Edit form: Min/Max images fields removed entirely.
- `SendDispatcher`'s per-slot dispatch logic simplifies from "select images (plural) → enqueue each" to "select at most one image → enqueue it if present."

**Dependencies/sequencing:** none blocking between 1.4 and 2.4 — both can start immediately and in either order. Story 2.3 (Master Schedule Fallback, currently `review`) should ideally reach `done` first purely for cadence hygiene, not because of any technical dependency.

## 6. Sprint Status Changes Applied

```diff
- epic-1: done
+ epic-1: in-progress
  1-1-first-run-registration-compliance-gate: done
  1-2-image-library-management: done
  1-3-receiver-configuration: done
+ 1-4-image-tagging-list-view: backlog
  epic-1-retrospective: optional
  epic-2: in-progress
  2-1-randomized-daily-selection: done
  2-2-offline-safe-queued-delivery: done
  2-3-master-schedule-fallback: review
+ 2-4-single-image-selection: backlog
  epic-2-retrospective: optional
```
