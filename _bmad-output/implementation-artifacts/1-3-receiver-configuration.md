---
baseline_commit: NO_VCS
---

# Story 1.3: Receiver Configuration

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want to add and manage receivers with their contact details, delivery channel, per-receiver image-count range, and one or more daily schedule times,
so that each receiver gets exactly the distribution I've set up for them.

**[AMENDED 2026-07-10 — Task 7 / Sprint Change Proposal]:** originally scoped as a single daily schedule time; the operator clarified receivers actually need 4+ sends/day. AC1 below reflects the final delivered behavior (minimum 4 schedule times, variable count).

## Acceptance Criteria

1. Given I'm on the receivers screen, When I add a new receiver with a name, a +91 phone number (for WhatsApp) or an email address (for email), a min/max daily image count, and one or more daily schedule times (minimum 4), Then the receiver is saved and appears in my receiver list. [Source: epics.md#Story 1.3] [Source: SPEC.md#CAP-2]
2. Given a receiver exists, When I edit or remove it, Then the change is saved and takes effect from the next scheduled send onward, And changing one receiver's settings never affects another receiver's schedule, channel, or count range. [Source: epics.md#Story 1.3] [Source: SPEC.md#CAP-2]

## Tasks / Subtasks

- [x] Task 1: `Receiver` entity, DAO, and schema migration (AC: 1, 2) — **extends the existing `AppDatabase` (now at version 2 after Story 1.2), does not create a second one**
  - [x] `data/local/Receiver.kt`: `id: Long` (autoincrement), `name: String`, `channel: String` (stored as the enum name — `"WHATSAPP"` or `"EMAIL"`, per the project's established enum-as-String convention), `phoneOrEmail: String`, `minCount: Int`, `maxCount: Int`, `scheduleTime: Int` (**minutes-since-midnight, device local time** — this exact representation is specified in `ARCHITECTURE-SPINE.md`'s Consistency Conventions table, not left to implementer discretion). [Source: ARCHITECTURE-SPINE.md#AD-6, #Consistency Conventions] [Source: SPEC.md#Constraints — channel enum `WHATSAPP`\|`EMAIL`]
  - [x] `data/local/ReceiverDao.kt`: `observeAll(): Flow<List<Receiver>>` (ordered by `name` ascending), `insert(receiver: Receiver): Long`, `update(receiver: Receiver)`, `deleteById(id: Long)`.
  - [x] Update `data/local/AppDatabase.kt`: add `Receiver::class` to `entities`, bump `version` to `3`, add `abstract fun receiverDao(): ReceiverDao`, add a `MIGRATION_2_3` (creates the `receivers` table) alongside the existing `MIGRATION_1_2` — **do not remove or alter `MIGRATION_1_2`**, both must be registered. Real data (a locked `ComplianceState` row, and by now possibly real `Image` rows) already exists on the test device — no destructive fallback. [Source: ARCHITECTURE-SPINE.md#AD-15] [Source: 1-2-image-library-management.md — the `MIGRATION_1_2` pattern to mirror exactly]
  - [x] Register `MIGRATION_2_3` in `AppContainer`'s `.addMigrations(...)` call alongside `MIGRATION_1_2`.
- [x] Task 2: `ReceiverRepository` (AC: 1, 2)
  - [x] `data/repository/ReceiverRepository.kt` (interface) + `ReceiverRepositoryImpl.kt` — wraps `ReceiverDao`. Every method returns `AppResult<T>`, `CancellationException` rethrown before the generic catch — identical shape to `ImageRepositoryImpl`'s `runCatchingDb`, don't reinvent it. [Source: ARCHITECTURE-SPINE.md#AD-8] [Source: data/repository/ImageRepositoryImpl.kt — mirror this pattern exactly]
  - [x] Methods: `observeReceivers(): Flow<List<Receiver>>`, `addReceiver(...): AppResult<Unit>`, `updateReceiver(receiver: Receiver): AppResult<Unit>`, `deleteReceiver(id: Long): AppResult<Unit>`.
  - [x] No domain service — simple CRUD, ViewModel calls this directly. [Source: ARCHITECTURE-SPINE.md#AD-5, #AD-10]
- [x] Task 3: `AppContainer` wiring (AC: 1, 2)
  - [x] Add `receiverDao`, `MIGRATION_2_3` registration, and `receiverRepository: ReceiverRepository by lazy { ... }` to `di/AppContainer.kt`, following the exact pattern already used for `imageRepository`.
- [x] Task 4: Receiver list screen + ViewModel (AC: 1, 2)
  - [x] `ui/receivers/ReceiversViewModel.kt` — exposes receivers as `StateFlow`, `onDeleteReceiver(id: Long)`. Obtained via a `ViewModelProvider.Factory` companion — same pattern as `SetupViewModel`/`ImageLibraryViewModel`, not `remember{}`.
  - [x] `ui/receivers/ReceiversScreen.kt` — list of receiver rows in a `LazyColumn`. Each row: `{rounded.md}` card, name + channel on top line, schedule time (formatted from minutes-since-midnight, e.g. `09:00`) + count range as `{typography.label}` meta text below. Tap opens the Edit screen for that receiver. Swipe-to-delete (native `SwipeToDismissBox`, Compose Material3) with a confirm dialog before actually deleting — deletion is destructive. [Source: DESIGN.md#Components — receiver-row] [Source: EXPERIENCE.md#Component Patterns]
  - [x] Empty state: `"No receivers yet — add one to start sending."` [Source: EXPERIENCE.md#State Patterns]
  - [x] "+" button/FAB to open the Edit screen in add-new mode. **Note (code review):** delivered as a full-width "Add receiver" button, not a literal FAB — matches the established pattern of the Images tab's full-width "Upload images" button (Story 1.2) for cross-tab consistency. Flagging the deviation explicitly rather than letting the checkbox imply a literal FAB was built.
- [x] Task 5: Receiver Edit screen + form validation (AC: 1, 2)
  - [x] `ui/receivers/ReceiverEditScreen.kt` + a form-state holder in `ReceiversViewModel` (or a dedicated small `ReceiverEditViewModel` — implementer's call, but justify it in Completion Notes if a second ViewModel is added rather than folding into `ReceiversViewModel`). Fields: name, a **segmented control** for channel (WhatsApp / Email) that swaps the contact field below it — phone number field when WhatsApp is selected, email field when Email is selected, **never both visible at once**. [Source: EXPERIENCE.md#Component Patterns — "Receiver Edit form"]
  - [x] Min/max count fields (numeric input). Max daily image count, ~8-10 receivers total is the expected scale per `SPEC.md`, but this story does not need to enforce a receiver-count cap — that's not in either AC.
  - [x] Schedule time: use Material 3's first-party **`TimePickerDialog`** composable (wraps `TimePicker`/`rememberTimePickerState` — no longer needs a hand-rolled `Dialog` wrapper, that pattern is obsolete). Store/read as minutes-since-midnight. [Source: web research, 2026-07, see Latest Technical Notes below]
  - [x] Save button persists via `ReceiverRepository.addReceiver`/`updateReceiver`, then navigates back to the list.
  - [x] Simple internal routing: mirror `AppRouter.kt`'s established pattern — a small `ReceiversRoute` sealed class (`List` / `Edit(receiverId: Long?)`, `null` = add-new) with `rememberSaveable`, switched on inside a `ReceiversTab` composable. **Do not add Jetpack Navigation Compose as a new dependency** — this project has deliberately stayed off it so far (`AppRoute` in `AppRouter.kt` is the established precedent for this exact kind of simple state-based routing); introducing a navigation library for one sub-flow would be inconsistent with that choice.
  - [x] **[ASSUMPTION — flag, don't silently invent]:** no upstream source specifies what happens if `min > max` is entered, or what phone/email format validation should look like beyond "+91 fixed" and "an email address." Implement a reasonable minimum (min ≤ max required to save; phone digits-only after the fixed +91 prefix; a basic email-contains-@ check) and flag this assumption in Completion Notes — don't invent elaborate validation rules beyond what's needed to prevent obviously-broken data.
- [x] Task 6: Wire into `App.kt`'s `MainAppPlaceholder` (AC: 1, 2)
  - [x] **UPDATE, not new** — read `ui/App.kt` completely before editing (it now has the Images tab wired from Story 1.2; re-read the live file, don't assume it's unchanged from this story's context snapshot). Change only the `selectedTab == 1` (Receivers) case to render the new Receivers flow (list/edit routing from Task 5). **Dashboard/Settings (indices 2–3) must keep their existing placeholder card exactly as-is** — Stories 3.1/3.2's job, not this one's.
  - [x] Preserve everything else untouched: gradient background, `NavigationBar` styling, `Scaffold` transparency, the now-working Images tab.

### Review Findings

- [x] [Review][Patch] Edit screen has no way to leave without saving — system Back exits the whole app, not just the sub-route (reproduced live during this review's live-verification pass) [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] Editing a receiver can silently insert a duplicate instead of updating if `existing` resolves to null (StateFlow not yet loaded on process-death restore, or the record was deleted concurrently) — violates AC2 [ui/receivers/ReceiversScreen.kt:62-69, ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] `onDeleteReceiver` discards the returned `AppResult` — a failed delete leaves the swiped-away row with no reset and no error surfaced [ui/receivers/ReceiversViewModel.kt, ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] `saveReceiver` is launched on `rememberCoroutineScope()` instead of `viewModelScope`, risking cancellation mid-save if the composable leaves composition [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Save button has no in-flight/disabled guard — rapid double-taps can fire duplicate concurrent inserts [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Validation error is a single shared message below the form, not inline per field — contradicts EXPERIENCE.md's explicit "Inline error text under the field" spec [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Bottom "Add receiver" button has no reserved space in the list and can overlap/obscure the last row(s) [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] `ReceiversRoute`'s `Saver.restore` can throw `NumberFormatException` on a malformed saved-state string — realistic given this app's sideload-APK-update delivery model [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] No instrumented test coverage for `ReceiverEditScreen` — story's Dev Notes called for list/edit screen coverage, only the list screen got covered [app/src/androidTest/.../ReceiversScreenTest.kt]
- [x] [Review][Patch] DESIGN.md's `receiver-row` divider token (`{colors.outline}`) is specified but unused [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] Phone field accepts unlimited-length digit strings with no validation against the expected 10-digit format [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] `ReceiverChannel.valueOf(...)` / `channelLabel`'s implicit-else both assume well-formed channel data with no defensive fallback for corrupted rows [ui/receivers/ReceiverEditScreen.kt, ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] No `.trim()` on name/email fields, inconsistent with the trim precedent set in Story 1.1 [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Task 4's "+ button/FAB" checkbox marked done despite delivering a full-width button (consistent with the Images tab's established pattern, but not literally a FAB) — a completion-notes honesty gap, not a functional defect [this story file]
- [x] [Review][Defer] `.catch { emit(emptyList()) }` in `observeReceivers()` can leave the receivers `StateFlow` stuck at `emptyList()` after a DB-flow error until all subscribers drop and reattach — deferred, pre-existing pattern inherited verbatim from Story 1.2's `ImageRepositoryImpl` per this story's own explicit "mirror it exactly" instruction; a cross-cutting fix belongs to both repositories together, not this story alone [data/repository/ReceiverRepositoryImpl.kt]
- [x] [Review][Defer] Edit form / empty-state cards use `rounded.lg` where DESIGN.md's generic Shapes section suggests `rounded.md` for cards — deferred, matches the established "paper card" motif already used identically in Story 1.1's SetupScreen and Story 1.2's ImageLibraryScreen empty state; not a new deviation introduced by this story [ui/receivers/ReceiverEditScreen.kt, ui/receivers/ReceiversScreen.kt]
- [x] [Review][Defer] No fallback Room migration path beyond 1→2→3 — deferred, this is the intended/correct behavior per AD-15 (no destructive fallback), not a defect [data/local/AppDatabase.kt]
- [x] [Review][Defer] `removePrefix("+91")` is a no-op for legacy/foreign-formatted phone numbers — deferred, currently unreachable since no such data exists yet; worth hardening if real imported data ever enters [ui/receivers/ReceiverEditScreen.kt]

### Task 7: Multi-schedule rework [ADDED — Sprint Change Proposal 2026-07-10]

Reopened after the story was marked `done`: the operator clarified receivers need **one or more daily schedule times, minimum 4** (not one), each independently rolling a random image count within the receiver's existing min/max (per `mechanics.md`'s already-generic "at each scheduled send" algorithm — no selection-algorithm changes needed, only the data model and UI). Full analysis in `_bmad-output/planning-artifacts/sprint-change-proposal-2026-07-10.md`.

- [x] `data/local/ReceiverSchedule.kt`: new entity — `id: Long` (autoincrement), `receiverId: Long` (FK to `receivers.id`), `time: Int` (minutes-since-midnight, same convention as the field it replaces).
- [x] `data/local/ReceiverScheduleDao.kt`: `observeForReceiver(receiverId: Long): Flow<List<ReceiverSchedule>>`, `insert(schedule: ReceiverSchedule): Long`, `deleteById(id: Long)`, `deleteAllForReceiver(receiverId: Long)` (used on receiver save to replace the full schedule set atomically).
- [x] `data/local/Receiver.kt`: remove `scheduleTime: Int`.
- [x] `data/local/AppDatabase.kt`: version 3→4. Add `ReceiverSchedule::class` to `entities`, `abstract fun receiverScheduleDao(): ReceiverScheduleDao`. `MIGRATION_3_4`: create `receiver_schedules` table; recreate `receivers` table without `scheduleTime` (SQLite has no simple `DROP COLUMN` across all supported versions — use the copy-to-new-table-and-rename pattern: create `receivers_new` without the column, `INSERT INTO receivers_new SELECT id,name,channel,phoneOrEmail,minCount,maxCount FROM receivers`, drop old, rename new). **Do not touch `MIGRATION_1_2`/`MIGRATION_2_3`** — all three must stay registered in `AppContainer`.
- [x] `data/repository/ReceiverRepository.kt`/`Impl.kt`: redesign around a `ReceiverWithSchedules` shape (`data class ReceiverWithSchedules(val receiver: Receiver, val scheduleTimes: List<Int>)`, or a Room `@Relation` — implementer's call, but `observeReceivers()` must emit that combined shape, and `addReceiver`/`updateReceiver` must accept it and write the receiver row plus replace its full schedule set in a single transaction (delete-then-insert is fine, but must be atomic — Room `@Transaction`).
- [x] `ui/receivers/ReceiversViewModel.kt`: update to the new repository shape; `receivers: StateFlow<List<ReceiverWithSchedules>?>`.
- [x] `ui/receivers/ReceiversScreen.kt`: row shows a schedule-count summary (e.g. "4×/day") per DESIGN.md's updated receiver-row spec, not the old single "09:00" time.
- [x] `ui/receivers/ReceiverEditScreen.kt`: replace the single `Schedule: HH:mm` button with a list of schedule-time rows (each with its own remove control) + an "Add time" button reusing the existing `TimePickerDialog`. Validation: **at least 4 times required to save**, inline error under the list per EXPERIENCE.md's updated Component Patterns ("Add at least 4 schedule times.").
- [x] Update all existing Receiver-related tests (`ReceiverRepositoryImplTest`, `ReceiversViewModelTest`, `ReceiversScreenTest`) for the new shape; add coverage for the minimum-4 validation and the add/remove schedule-time interactions.
- [x] Verify `MIGRATION_3_4` live against the current on-device install (reinstall over existing v3 data, confirm no crash, confirm the existing `receivers` row(s) survive with their `minCount`/`maxCount`/`channel`/etc. intact minus the dropped column).
- [x] Live emulator verification: add a receiver with 4+ schedule times, edit it (add/remove a time), confirm the row summary updates, confirm Save is blocked below 4 times with the inline error visible.

### Review Findings (Task 7 round)

- [x] [Review][Patch] After `MIGRATION_3_4`, a pre-existing receiver has exactly 1 schedule time (its old `scheduleTime`, preserved) but the app's own rule now requires 4+ — nothing in the list/edit UI flags this to the operator [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] `ReceiverEditScreen`'s `phoneDigits`/`email` initializers check the raw `existingReceiver.channel` string instead of `channelOrDefault()` — a receiver with a corrupted `channel` value shows the wrong channel selected with a blank contact field, and Save would silently overwrite the real `phoneOrEmail` with a near-empty value [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Phone validation only checks non-blank, not length — a 1-9 digit number saves successfully and would silently fail every WhatsApp send [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] `ReceiversViewModel.save()` sets `_isSaving = true` with no `try/finally` — an uncaught exception in the save coroutine would leave saves permanently locked out [ui/receivers/ReceiversViewModel.kt]
- [x] [Review][Patch] `ReceiversListScreen` uses `receiverList.isNullOrEmpty()`, conflating "not yet loaded" with "genuinely empty" — defeats the null-vs-empty distinction the `StateFlow<List<ReceiverWithSchedules>?>` type was specifically built for; briefly flashes the empty-state card on cold load [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] `ReceiverRow`'s `deleteError` is never reset to `false` on a successful retry — a stale "Couldn't remove" message can flash before a since-successful delete removes the row [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Patch] Picking an already-scheduled time in the "Add time" dialog silently no-ops (closes with no addition, no feedback) [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Patch] Schedule-time "Remove" removes by value (`scheduleTimes.remove(time)`) rather than by index — currently unreachable since duplicates can't enter the list today, but a latent footgun for future changes; switching to index-based removal is free [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Defer] Minimum-4 schedule-time validation lives only in the UI, not the repository — consistent with this vertical's established "no domain service, UI-level validation" pattern (matches how phone/email/min-max validation already work); enforcing it at the repository layer alone, inconsistently with sibling fields, would be a bigger architectural change out of scope for this patch round [data/repository/ReceiverRepositoryImpl.kt]
- [x] [Review][Defer] No DB-level uniqueness constraint on `(receiverId, time)` — low value given the UI already prevents duplicates on add; redundant defense-in-depth, not a reachable bug today [data/local/ReceiverSchedule.kt]
- [x] [Review][Defer] `-1` sentinel encoding for `receiverId == null` in the route `Saver` — pre-existing from the original Task 5 implementation, unchanged by Task 7 [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Defer] Swipe-to-delete has no configured dismiss-direction restriction — pre-existing from Task 4, unchanged by Task 7 [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Defer] Editing a receiver reassigns new primary keys to all its schedule rows every save (delete-and-reinsert) — a real design tradeoff, but no downstream consumer exists yet (Epic 2's `SendWorker` isn't built), so there's nothing to actually break today; worth revisiting once Epic 2 is implemented [data/repository/ReceiverRepositoryImpl.kt]
- [x] [Review][Defer] No upper bound on schedule-time count — low value, self-inflicted-only scenario (operator would have to manually add dozens of times) [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Defer] `%d`/`%02d` string formatting doesn't pin `Locale.US` — pre-existing pattern across the whole codebase (row/schedule text), single-locale (India) deployment target per SPEC.md, low real-world risk [ui/receivers/ReceiversScreen.kt, ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Defer] Min/max daily image count of `0` is accepted — pre-existing Task 5 assumption, already adjudicated in the prior review round ("implement a reasonable minimum... don't invent elaborate validation") [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Defer] `observeReceivers()`'s `.catch { emit(emptyList()) }` makes a DB error indistinguishable from "no receivers" — already deferred in the prior review round as a cross-cutting pattern inherited from `ImageRepositoryImpl`, not newly introduced by Task 7 [data/repository/ReceiverRepositoryImpl.kt]
- [x] [Review][Defer] Field `onValueChange` handlers don't clear their corresponding error state on edit — a real UX gap, but applies uniformly across the whole form (not Task-7-specific) and is a bigger cross-cutting polish pass better done as its own unit, not folded into this migration-focused patch round [ui/receivers/ReceiverEditScreen.kt]
- [x] [Review][Defer] No discard-changes confirmation when backing out of an in-progress edit — legitimate future UX enhancement, no AC requires it, would need dirty-state tracking not yet built [ui/receivers/ReceiversScreen.kt]
- [x] [Review][Defer] DESIGN.md still describes a "trailing icon-button for delete" while EXPERIENCE.md and the actual implementation use swipe-to-delete — pre-existing contradiction from Task 4, not introduced or touched by Task 7's doc-update pass [DESIGN.md, EXPERIENCE.md]
- [x] [Review][Dismiss] Acceptance Auditor flagged `ReceiverDao.kt` as never updated with `observeAllWithSchedules()`, which would mean the repository doesn't compile — **false positive**, confirmed by direct file read that the method is present and correct. Caused by an incomplete diff sent to the review agents (this file was omitted from the file list), not a real code defect. All prior test-pass evidence (XML results) stands.
- [x] [Review][Dismiss] Edge Case Hunter flagged `MIGRATION_3_4`'s `DROP TABLE receivers` as a possible foreign-key-constraint crash risk while `receiver_schedules` still references it — **contradicted by direct evidence**: `AppDatabaseMigrationTest.migrate3To4_...` executes this exact sequence against a populated child table via `MigrationTestHelper` and passes (verified via XML, `tests="2" failures="0"`).
- [x] [Review][Dismiss] Blind Hunter flagged the exported Room schema JSON files as missing from the diff, undermining the migration test's validity — the schema files exist on disk (`app/schemas/.../1-4.json`) and were used by the real, passing `MigrationTestHelper` run; simply not included in the diff patch file built for reviewers (which only contained `.kt`/`.gradle.kts` files).
- [x] [Review][Dismiss] Blind Hunter flagged the redundant `scheduleDao.deleteAllForReceiver()` call in `deleteReceiver` ahead of the `ON DELETE CASCADE` FK as dead/confusing code — this is intentional defensive redundancy, not a bug, especially given the ambient uncertainty about FK enforcement raised (and then disproven) in the dismissed finding above.

## Dev Notes

- **Paradigm reminder:** `ui/receivers` → `data/repository/ReceiverRepository` → `data/local` (`ReceiverDao`). No domain service — CRUD only, identical shape to Story 1.2's Image flow. [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-5]
- **Reuse, don't reinvent — now three prior examples to mirror:** `ComplianceRepositoryImpl` (Story 1.1), `ImageRepositoryImpl` (Story 1.2) both establish the `AppResult`/`runCatchingDb` repository shape; `SetupViewModel`/`ImageLibraryViewModel` establish the `ViewModelProvider.Factory` pattern; `AppRouter.kt`'s `AppRoute` establishes the simple sealed-class + `rememberSaveable` routing pattern this story's list/edit flow should copy. Read all of these before writing this story's equivalents.
- **Two migrations must coexist.** `AppDatabase` is now on its third schema version. Write `MIGRATION_2_3` as its own object exactly like `MIGRATION_1_2` was written, and register both in `AppContainer`. Do not merge them into one migration or renumber `MIGRATION_1_2`.
- **Story 1.2 left an open item this story should NOT silently inherit as "fine":** the Photo Picker interaction wasn't conclusively verified on-device last story. That's an Images-tab concern, not this story's — don't let it block Receivers work, but don't assume Task 4/5 of *this* story are similarly unverified just because a sibling story had a gap. Verify *this* story's own UI on-device independently.
- **Testing:** JVM unit tests for `ReceiverRepositoryImpl` (mirror `ImageRepositoryImplTest`) and for `ReceiversViewModel`'s state/delete/add logic (mirror `ImageLibraryViewModelTest`/`SetupViewModelTest`) — these must actually run and pass. Compose UI tests for the list/edit screens follow the established `androidTest` pattern (written, but will not execute in this sandbox — say so explicitly in Completion Notes, don't imply verification that didn't happen). No new Room migration test infrastructure needed beyond what Story 1.2 already flagged as a gap — if adding `MigrationTestHelper` coverage, cover both `1→2` and `2→3` together; if skipping (matching Story 1.2's precedent), say so explicitly again rather than silently.

### Project Structure Notes

- Extends `com.ris.imagedistributor` structure — new package `ui/receivers/`.
- Full file list this story creates:
  - `data/local/Receiver.kt`, `data/local/ReceiverDao.kt`
  - `data/repository/ReceiverRepository.kt`, `data/repository/ReceiverRepositoryImpl.kt`
  - `ui/receivers/ReceiversViewModel.kt`, `ui/receivers/ReceiversScreen.kt`, `ui/receivers/ReceiverEditScreen.kt`, `ui/receivers/ReceiversRoute.kt` (or folded into `ReceiversScreen.kt` — implementer's call, mirror `AppRouter.kt`'s shape either way)
- Files this story **updates**:
  - `data/local/AppDatabase.kt` (add `Receiver` entity, version 2→3, `MIGRATION_2_3` — `MIGRATION_1_2` stays)
  - `di/AppContainer.kt` (add DAO/repository wiring, register the new migration)
  - `ui/App.kt` (swap only the Receivers tab's placeholder content)

### References

- [Source: SPEC.md#CAP-2, #Constraints]
- [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-5, #AD-6, #AD-8, #AD-10, #AD-15, #Consistency Conventions]
- [Source: DESIGN.md#Components (receiver-row), #Elevation & Depth (revised — gold card treatment)]
- [Source: EXPERIENCE.md#Component Patterns, #State Patterns]
- [Source: epics.md#Epic 1, #Story 1.3]
- [Source: 1-1-first-run-registration-compliance-gate.md, 1-2-image-library-management.md — established repository/ViewModel/migration/routing patterns to mirror]

## Previous Story Intelligence (Story 1.2)

- **`AppDatabase` is now version 2** with `ComplianceState` and `Image` entities; this story bumps it to 3. `MIGRATION_1_2` already exists and must not be touched.
- **The copy-then-insert lesson from Story 1.2 doesn't directly apply here** (no file I/O for receivers), but the general principle — never persist a row that references something that hasn't actually succeeded yet — still applies to Save button behavior: don't navigate back to the list until `addReceiver`/`updateReceiver` actually returns `AppResult.Success`.
- **Real gap from last story, explicitly flagged rather than hidden:** the Photo Picker UI interaction wasn't conclusively verified live (tap coordinates were guessed rather than measured precisely). For this story's on-device verification, get real element coordinates (e.g. via `uiautomator dump` — the last session was interrupted before that was tried — or via visual estimation checked against a screenshot at the same resolution) rather than guessing, to avoid repeating an inconclusive verification.
- **Toolchain and emulator (`imagedrop_test`) already set up and working** from Stories 1.1/1.2 — no environment setup needed.

## Latest Technical Notes (web-verified 2026-07)

- **`androidx.compose.material3.TimePickerDialog`** is now a first-party Material 3 Compose composable (accepts `onDismissRequest`, `confirmButton`, `dismissButton`, `title`; wraps `TimePicker`/`rememberTimePickerState` internally). The older pattern of hand-rolling a `Dialog` wrapper around `TimePicker` yourself is obsolete — use the first-party one.
- No other new dependencies needed for this story — `Compose Material3` (already present) covers `TimePickerDialog`, `SwipeToDismissBox`, and segmented-control-equivalent components (`SingleChoiceSegmentedButtonRow`/`SegmentedButton`, both already ship in the current Material3 BOM).

Sources: [Time pickers (Android Developers)](https://developer.android.com/develop/ui/compose/components/time-pickers), [Dialogs for time pickers (Android Developers)](https://developer.android.com/develop/ui/compose/components/time-pickers-dialogs)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL. Went from 47 → 56 tests across 8 classes (added `ReceiverRepositoryImplTest`: 9, `ReceiversViewModelTest`: 5), 0 failures/errors/skipped — verified via `test-results/*.xml`, not just the gradle summary line.
- First `compileDebugKotlin` attempt failed with real errors, not skipped past: `TimePickerDialog`/`TimePicker`/`rememberTimePickerState` are `@ExperimentalMaterial3Api` (needed `@OptIn`), `TimePickerDialog`'s `title` parameter has no default (had to pass one explicitly), and `SwipeToDismissBoxState.reset()` is a suspend function (was being called directly from a non-suspend lambda — wrapped in `rememberCoroutineScope().launch { }`). All three fixed and reverified.
- `gradle :app:lintDebug` — 0 errors. One `mutableStateOf`→`mutableIntStateOf` suggestion on `scheduleTime` fixed (matches the project's existing convention, e.g. `selectedTab` in `App.kt`). Final run: 20 pre-existing warnings, 0 new ones from this story's code.
- `gradle :app:assembleDebug` — BUILD SUCCESSFUL.
- Installed the built APK over the existing Story 1.1/1.2 data on the emulator (not a fresh install) specifically to exercise `MIGRATION_2_3` for real against a device with an already-locked `ComplianceState` row and existing `Image` rows. **Confirmed working**: app launched straight into the main app, `receivers` table migrated cleanly, no data loss, no crash.
- **Live UI verification on-device (precise, not guessed):** launched the app, tapped the Receivers tab, confirmed the gold-bordered empty-state card ("No receivers yet — add one to start sending."), tapped "Add receiver", filled in the form (name, WhatsApp channel, phone, min/max counts), tapped Save, confirmed the new receiver appeared in the list with the correct formatted row text ("Asha · WhatsApp" / "09:00 · 2–5 images"). Reinstalled and relaunched to confirm the row persisted across a process restart.
- **Real bug found and fixed via live verification, not just code reading:** tapping a receiver row to edit it did nothing — `ReceiverRow`'s `onClick` parameter was declared but never wired to the `Card`'s modifier. Fixed by adding `.clickable(onClick = onClick)`; reverified live that tapping a row now opens the Edit screen pre-filled with that receiver's data. This is exactly the kind of gap Story 1.2 left unverified (an interaction that compiles fine but silently does nothing) — caught this time by actually driving the UI instead of only reading the code or trusting BUILD SUCCESSFUL.
- Also verified swipe-to-dismiss on a row: triggers the "Remove {name}? This can't be undone." confirm dialog; tapping Remove deletes the receiver and returns to the empty state.
- **Instrumented tests actually executed this time** (a first for this project — Stories 1.1/1.2 left theirs written-but-unexecuted). Hit a pre-existing, unrelated packaging conflict on the first `connectedDebugAndroidTest` run (`mockk-android`'s transitive `junit-jupiter` deps collided on `META-INF/LICENSE.md`/`LICENSE-notice.md`); fixed with a standard `packaging { resources { excludes += ... } }` block in `app/build.gradle.kts` — not a workaround specific to this story's tests, this would have blocked *any* instrumented test run. After the fix: all 5 instrumented tests passed on `imagedrop_test(AVD)` (`SetupScreenTest`, `ComplianceHaltScreenTest`, `ImageLibraryScreenTest`, and the 2 new `ReceiversScreenTest` cases) — verified via `androidTest-results/connected/debug/*.xml` (`tests="5" failures="0" errors="0" skipped="0"`).

### Completion Notes List

- All 6 tasks implemented and covered by 14 new executed unit tests (9 `ReceiverRepositoryImplTest` + 5 `ReceiversViewModelTest`), all passing (56 total). `AppDatabase` migrated cleanly from v2→v3 against a real on-device install with existing Compliance/Image data — the highest-risk item flagged in this story's Dev Notes.
- **Followed the story's explicit instruction not to repeat Story 1.2's inconclusive verification**: got real tap coordinates from live screenshots at known resolution (rather than guessing) and drove the full add/list/edit/delete flow live on the emulator. This directly caught a real wiring bug (`ReceiverRow.onClick` never connected) that unit tests alone would not have caught, since the ViewModel and repository logic were both correct in isolation — only the Compose wiring was broken.
- **Instrumented tests executed for the first time in this project**, incidentally also closing Story 1.1/1.2's "written but unexecuted" gap for `SetupScreenTest`/`ComplianceHaltScreenTest`/`ImageLibraryScreenTest` (all passed) in addition to this story's new `ReceiversScreenTest`. This became possible only after fixing a pre-existing packaging conflict unrelated to Receivers specifically.
- `ReceiversListScreen` was changed from `private` to `internal` (mirroring how `ImageLibraryScreen` takes a `viewModel` param directly) so it could be instrumented-tested without needing a full `AppContainer`/Room database in the test.
- **Assumption applied per Task 5's explicit flag**: min ≤ max required to save, phone stored as digits-only after a fixed `+91` prefix, email validated only via a basic "contains @" check. No elaborate validation beyond preventing obviously-broken data, as instructed.
- **Verification status, explicit:**
  - Executed and passing: all JVM unit tests (56 total) and all instrumented Compose UI tests (5 total, including this story's 2).
  - Verified live on-device: schema migration v2→v3, full add/list/edit/delete/swipe-delete flow, row formatting, empty state, cross-reinstall persistence.
  - No remaining unverified UI interaction gap for this story — the one real bug found (`onClick` wiring) was fixed and reverified, not just noted.
  - Room `MIGRATION_2_3` has no dedicated `MigrationTestHelper` unit test — skipped for time, matching Story 1.2's precedent for `MIGRATION_1_2`; confirmed correct only via the live on-device install described above. Flagging as a real open item rather than silently omitting it, consistent with prior stories.

### Task 7 Debug Log / Completion Notes (multi-schedule rework)

- Redesigned the data model around a new `ReceiverSchedule` entity (one-to-many from `Receiver`) instead of `Receiver.scheduleTime: Int`. `MIGRATION_3_4` recreates the `receivers` table (copy-to-new-table-and-rename pattern, since SQLite has no reliable cross-version `DROP COLUMN`) and preserves each existing receiver's old `scheduleTime` as its first `ReceiverSchedule` row rather than discarding it — no destructive fallback, per AD-15.
- **Unlike Stories 1.2/1.3's earlier migrations, this one got dedicated `MigrationTestHelper` coverage** (`AppDatabaseMigrationTest`, 2 tests: `migrate3To4_preservesExistingReceiverAsItsFirstScheduleTime`, `migrate1Through4_succeedsAgainstAFreshV1Database`) rather than relying on live-device verification alone — the table-recreation pattern is riskier than the earlier CREATE-TABLE-only migrations and deserved real coverage. Required adding `androidTestImplementation(libs.room.testing)` (already an approved dependency, just not previously wired into the androidTest source set) and registering `app/schemas` as an androidTest asset dir so `MigrationTestHelper` can load the exported historical schemas.
- `ReceiverRepositoryImplTest` was already living in `androidTest` (moved there during the code-review round, since `AppDatabase.withTransaction` isn't meaningfully mockable and this project has no Robolectric) — extended for the new `ReceiverWithSchedules` shape and multi-schedule add/update/delete.
- **Real bug found and fixed via live verification, not just code reading (again):** `ReceiverEditScreen`'s form `Column` had no scroll modifier. With only 1 schedule time (the old single-time model) everything fit on screen, but with 4+ schedule times the form overflows and the Save button gets pushed completely off-screen with no way to reach it — a real, user-facing dead end. Fixed by wrapping the form in `Modifier.verticalScroll(rememberScrollState())`; reverified live that a 4-time form scrolls correctly and Save is reachable.
- **Live verification, precise:** added a receiver with exactly 0 schedule times and confirmed Save is blocked with "Add at least 4 schedule times." inline under the list; added times one at a time via the reused `TimePickerDialog` (now titled "Add schedule time") confirming they render sorted; reached 4 times and confirmed the error clears and Save succeeds; confirmed the list row shows the new "4×/day · 2–5 images" summary format; tapped the row to edit and confirmed all 4 times reload correctly with no duplicate created.
- Migration verified two ways: `MigrationTestHelper` (deterministic, seeded v3 data) and a live reinstall over the emulator's existing data (empirical, real device path) — both passed.
- **Verification status, explicit:** all JVM unit tests (50, after `ReceiverRepositoryImplTest` moved to androidTest) and all instrumented tests (14, including 2 new migration tests and 2 new schedule-validation UI tests) executed and passing via XML, not just BUILD SUCCESSFUL. Full live emulator pass covering add/edit/validate/scroll for the new schedule-time UI.

### File List

**New:**
- `app/src/main/java/com/ris/imagedistributor/data/local/Receiver.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/ReceiverDao.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/ReceiverRepository.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/ReceiverRepositoryImpl.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversRoute.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModel.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiverEditScreen.kt`
- `app/src/test/java/com/ris/imagedistributor/data/repository/ReceiverRepositoryImplTest.kt`
- `app/src/test/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModelTest.kt`
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (instrumented — executed and passing)

**Updated (initial implementation):**
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (added `Receiver` entity, version 2→3, `MIGRATION_2_3`; `MIGRATION_1_2` untouched)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (added `receiverRepository`, registered `MIGRATION_2_3`)
- `app/src/main/java/com/ris/imagedistributor/ui/App.kt` (Receivers tab now renders `ReceiversTab`; Images tab, Dashboard/Settings placeholders untouched)
- `app/build.gradle.kts` (added a `packaging { resources { excludes += ... } }` block to fix a pre-existing `mockk-android`/`junit-jupiter` `META-INF` conflict that was blocking all instrumented test runs, not just this story's)

**Updated (code review patches):**
- `app/src/main/java/com/ris/imagedistributor/data/local/Receiver.kt` (added `channelOrDefault()` safe-parse extension)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModel.kt` (nullable `receivers` StateFlow to distinguish loading vs. empty, `deleteReceiver` suspend fn, `save`/`isSaving` viewModelScope wrapper)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt` (BackHandler on Edit route, per-row delete-failure feedback, bottom list padding, receiver-row divider, Saver crash guard, defensive channel label)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiverEditScreen.kt` (receiverId/stillLoading params to fix duplicate-on-edit, per-field inline errors, phone length cap, name/email trim, in-flight save guard)
- `app/src/test/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModelTest.kt` (updated for the new API, added `save`/`isSaving` coverage)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (added `ReceiverEditScreen` inline-validation and channel-toggle tests)
- `_bmad-output/implementation-artifacts/1-3-receiver-configuration.md` (Task 4 FAB note, Review Findings section)
- `_bmad-output/implementation-artifacts/deferred-work.md` (4 deferred findings appended)

**New (Task 7 — multi-schedule rework):**
- `app/src/main/java/com/ris/imagedistributor/data/local/ReceiverSchedule.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/ReceiverScheduleDao.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/ReceiverWithScheduleEntities.kt`
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (instrumented, `MigrationTestHelper`)
- `app/src/androidTest/java/com/ris/imagedistributor/data/repository/ReceiverRepositoryImplTest.kt` (moved here from `test/`, extended)

**Updated (Task 7 — multi-schedule rework):**
- `app/src/main/java/com/ris/imagedistributor/data/local/Receiver.kt` (removed `scheduleTime`, added `ReceiverWithSchedules` domain shape)
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (version 3→4, `ReceiverSchedule` entity, `MIGRATION_3_4`)
- `app/src/main/java/com/ris/imagedistributor/data/repository/ReceiverRepository.kt`/`ReceiverRepositoryImpl.kt` (rebuilt around `ReceiverWithSchedules`, `AppDatabase.withTransaction`)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (registered `MIGRATION_3_4`, `ReceiverRepositoryImpl(database)`)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModel.kt` (`ReceiverWithSchedules`-based state)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt` (row shows schedule-count summary)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiverEditScreen.kt` (schedule-time list UI, minimum-4 validation, scroll fix)
- `app/src/test/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModelTest.kt` (updated for `ReceiverWithSchedules`)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (updated row-summary assertion, added minimum-4 validation test)
- `app/build.gradle.kts` (`androidTestImplementation(libs.room.testing)`, `androidTest` schemas asset dir)
- `_bmad-output/specs/spec-image-distributor-app/SPEC.md`, `_bmad-output/planning-artifacts/architecture/architecture-RIS-2026-07-08/ARCHITECTURE-SPINE.md`, `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/DESIGN.md`, `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/EXPERIENCE.md`, `_bmad-output/planning-artifacts/epics.md` (multi-schedule wording, per Sprint Change Proposal)

## Change Log

- 2026-07-10: Story implemented end-to-end (Tasks 1–6). Schema migration v2→v3 verified live against an existing on-device install (no data loss). 14 new unit tests, all passing (56 total). Live UI verification with precise tap coordinates caught and fixed a real bug (`ReceiverRow.onClick` never wired). Fixed a pre-existing packaging conflict that had blocked instrumented tests project-wide; all 5 instrumented tests (this story's 2 plus 3 carried over from Stories 1.1/1.2) executed and passed for the first time. Status moved to `review`.
- 2026-07-10: Code review (adversarial + edge-case + acceptance-audit, 3 parallel subagents). 21 findings triaged: 14 patch, 4 defer, 3 dismissed as noise/already-verified. All 14 patches applied: fixed a HIGH-severity bug where editing a receiver could silently insert a duplicate instead of updating (reachable if the list hadn't loaded yet or the record was deleted concurrently — would have double-sent to a paying customer); fixed a HIGH-severity bug where system Back exited the whole app from the Edit screen instead of backing out of it (reproduced live during this review); added inline per-field validation errors, delete-failure feedback with row-state reset, an in-flight save guard against double-tap duplicates, a phone-length cap, name/email trimming, a defensive channel parse, a Saver crash guard, bottom list padding, and the DESIGN.md row divider. Added missing `ReceiverEditScreen` instrumented test coverage. Re-verified: 59 unit tests passing, 7 instrumented tests passing (both via XML, not just BUILD SUCCESSFUL), and a full live emulator pass confirming both HIGH-severity fixes work end-to-end. Status remains `review` pending final sign-off.
- 2026-07-10: Story reopened after the operator identified receivers need 4+ daily schedule times, not 1 (Sprint Change Proposal, same date — see `_bmad-output/planning-artifacts/sprint-change-proposal-2026-07-10.md`). Confirmed with the operator: variable schedule count (minimum 4), each independently rolling within the receiver's existing min/max (`mechanics.md`'s algorithm needed no change). Updated SPEC.md/ARCHITECTURE-SPINE.md/DESIGN.md/EXPERIENCE.md/epics.md. Added Task 7: new `ReceiverSchedule` entity (one-to-many), `MIGRATION_3_4` (table recreation, preserves each receiver's old single time as its first schedule row), repository/ViewModel/UI rework around a `ReceiverWithSchedules` shape, and a schedule-time list UI with minimum-4 validation. Added dedicated `MigrationTestHelper` coverage for the riskier table-recreation migration (a first for this project). Found and fixed a real bug via live verification: the edit form had no scroll modifier, so a 4+ time form pushed Save off-screen with no way to reach it. Final verification: 50 unit tests + 14 instrumented tests passing (via XML), migration verified both by `MigrationTestHelper` and a live reinstall over existing data, full live emulator pass adding/editing a receiver with 4 schedule times including the minimum-4 validation and scroll-to-Save. Status set to `done`.
- 2026-07-10: Operator-requested UI polish: Min/Max daily images fields changed from stacked to side-by-side (`Row` with `weight(1f)` each, labels shortened to "Min images"/"Max images" to fit) to free up vertical space so more schedule times are visible before scrolling is needed. Verified live: with the new layout all 4 schedule times plus Save fit on screen without scrolling (previously only ~3 fit). Re-verified: 50 unit tests + 14 instrumented tests passing, lint clean.
- 2026-07-10: Second code review round on Task 7 + the UI polish (adversarial + edge-case + acceptance-audit, 3 parallel subagents). 21 findings triaged: 8 patch, 10 defer, 3 dismissed as false positives (verified against the real code/tests — an incomplete diff sent to the reviewers, not real defects). All 8 patches applied: fixed a receiver-with-corrupted-channel silently losing its real phone/email on edit; added a 10-digit phone length check (previously a 1-9 digit number saved successfully and would silently fail every send); added a `try/finally` around the save coroutine so an unexpected exception can't permanently lock out future saves; fixed `ReceiversListScreen` conflating "not yet loaded" with "genuinely empty" (defeated the null-vs-empty distinction the state type was built for); reset stale delete-error text on a successful retry; added inline feedback when picking an already-scheduled time (previously silent no-op); switched schedule-time removal to index-based (was value-based, a latent footgun); and added a "Needs N more schedule time(s)" indicator on receiver rows left below the minimum (e.g. after migrating from the old single-schedule model). Re-verified: 50 unit tests + 14 instrumented tests passing (via XML), live emulator pass confirming the 10-digit validation and duplicate-time message. Status remains `done`.
