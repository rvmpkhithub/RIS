---
baseline_commit: NO_VCS
---

# Story 2.4: Single-Image Selection

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want each scheduled send to deliver exactly one image instead of a random batch,
so that receivers get a single image per occurrence and I no longer need to configure a min/max count per receiver.

## Acceptance Criteria

1. Given a receiver's scheduled send time has arrived, when the app selects an image for that receiver, then it selects exactly one currently-active image, excluding any image already sent to that receiver in the last 7 days, falling back to a repeat only if every active image has already been sent within that window, and sending nothing if there are no active images at all. [Source: epics.md#Story 2.4] [Source: mechanics.md#Selection algorithm (CAP-3)]
2. Given I'm adding or editing a receiver, when I view the receiver form, then there is no min/max image count field — only contact details, channel, and optional schedule times. [Source: epics.md#Story 2.4] [Source: SPEC.md#CAP-2]
3. Given a receiver configured before this story shipped (with an existing min/max count), when the app upgrades, then the min/max count is dropped entirely from storage — no data migration path preserves it, since it no longer has any purpose. [Source: epics.md#Story 2.4] [Source: sprint-change-proposal-2026-07-13.md#5. Detailed Change Proposals]

**Scope boundary:** this story revisits Story 1.3's shipped `Receiver` entity/form (drops `minCount`/`maxCount` — a destructive-but-intentional schema change using the same copy-and-rename migration pattern already used once before in `MIGRATION_3_4`) and Story 2.1's shipped `ImageSelectionEngine` (removes the random-count-Z logic, always selects exactly 1 image or none). It does **not** touch `ReceiverSchedule`'s schema, the master-schedule fallback mechanism (Story 2.3's `.ifEmpty { masterScheduleTimes }` in `SendDispatcher`), the compliance gate, the delivery/retry/offline-recovery logic (Story 2.2), or `Image`'s tagging fields (Story 1.4) — only how many images get selected and enqueued per due schedule slot.

## Tasks / Subtasks

- [x] Task 1: Drop `minCount`/`maxCount` from `Receiver` — entity + migration 9→10 (AC: 2, 3)
  - [x] `data/local/Receiver.kt`: remove `val minCount: Int` and `val maxCount: Int` from the `Receiver` data class. Leave `id`/`name`/`channel`/`phoneOrEmail` untouched. **Do not touch** `ReceiverWithSchedules`, `ReceiverChannel`, or `channelOrDefault()` — none of them reference the count fields.
  - [x] `data/local/AppDatabase.kt`: bump `version` to `10`, add `MIGRATION_9_10`, mirroring `MIGRATION_3_4`'s exact copy-and-rename shape (SQLite has no reliable cross-version `DROP COLUMN`):
    ```kotlin
    /**
     * Drops `minCount`/`maxCount` from `receivers` (Story 2.4 — every scheduled send now
     * delivers exactly one image, making a per-receiver count range meaningless). Recreated via
     * the same copy-and-rename pattern MIGRATION_3_4 already established. Destructive and
     * intentional — no migration path preserves the dropped columns' values (epics.md#Story
     * 2.4's own AC3: "no data migration path preserves it, since it no longer has any purpose").
     */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE `receivers_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `channel` TEXT NOT NULL, `phoneOrEmail` TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO `receivers_new` (`id`, `name`, `channel`, `phoneOrEmail`) " +
                    "SELECT `id`, `name`, `channel`, `phoneOrEmail` FROM `receivers`"
            )
            db.execSQL("DROP TABLE `receivers`")
            db.execSQL("ALTER TABLE `receivers_new` RENAME TO `receivers`")
        }
    }
    ```
    **Do not touch `MIGRATION_1_2` through `MIGRATION_8_9`.** `receiver_schedules` (a separate table, foreign-keyed to `receivers.id`) is untouched by this migration — dropping and recreating `receivers` preserves its `id` values exactly (same copy order as `MIGRATION_3_4`), so existing `receiver_schedules` rows keep resolving correctly with no changes needed on that side.
  - [x] `di/AppContainer.kt`: add `AppDatabase.MIGRATION_9_10` to the `.addMigrations(...)` list (currently ends at `MIGRATION_8_9`). No other `AppContainer` changes — `ImageSelectionEngine`'s constructor never took `minCount`/`maxCount` (they were call-time params on `selectImagesFor`, removed in Task 2), so nothing to rewire there.

- [x] Task 2: Simplify `ImageSelectionEngine` to single-image selection (AC: 1)
  - [x] `domain/ImageSelectionEngine.kt`: replace `selectImagesFor(receiverId: Long, minCount: Int, maxCount: Int): AppResult<List<Image>>` with `selectImageFor(receiverId: Long): AppResult<Image?>` — a `null` value inside `AppResult.Success` means "nothing to send" (zero active images), distinct from `AppResult.Failure` (a real error). Implement the 4-step algorithm from `mechanics.md#Selection algorithm (CAP-3)`:
    ```kotlin
    suspend fun selectImageFor(receiverId: Long): AppResult<Image?> {
        val active = when (val result = imageRepository.getActiveImages()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        if (active.isEmpty()) return AppResult.Success(null) // step 4 — nothing to select, no query needed

        val since = Instant.now().minus(7, ChronoUnit.DAYS)
        val excludedIds = when (val result = transmissionRepository.getRecentlySentImageIds(receiverId, since)) {
            is AppResult.Success -> result.value.toSet()
            is AppResult.Failure -> return result
        }

        // Step 1: eligible pool = active minus anything sent to this receiver in the last 7 days.
        val eligible = active.filterNot { it.id in excludedIds }
        // Step 2/3: prefer the eligible pool; if it's empty (every active image already sent
        // within 7 days), fall back to allowing a repeat from the full active pool.
        val pool = eligible.ifEmpty { active }

        return AppResult.Success(pool.random(random))
    }
    ```
    Remove the old `minCount < 0 || maxCount < minCount || maxCount == Int.MAX_VALUE` validation entirely — there's nothing left to validate, since there are no caller-supplied bounds anymore (`FailureReason.INVALID_INPUT` is still used elsewhere in the codebase, just not by this method going forward).
  - [x] **Deliberate short-circuit, not a spec deviation**: checking `active.isEmpty()` *before* querying `transmissionRepository.getRecentlySentImageIds(...)` (rather than after, as the original code's variable ordering implied) avoids a wasted DB query when there's nothing to select regardless — the query's result can't change an already-empty active list into a non-empty one. Behaviorally identical to querying first and then checking; just skips pointless work. Do not "restore" the old ordering.
  - [x] Class-level doc comment: keep the `CAP-3 selection algorithm, mechanics.md#Selection algorithm — [AD-10]` and injectable-`random`-for-testability paragraphs (both still accurate) — just drop any implied reference to a variable count (there is none to describe anymore beyond "picks exactly one").
  - [x] **Do not touch** `imageRepository`/`transmissionRepository` constructor params, or `TransmissionRepository`/`ImageRepository` themselves — this task is scoped entirely to `ImageSelectionEngine`'s own method body and signature.

- [x] Task 3: Update `SendDispatcher`'s dispatch loop for single-image selection (AC: 1)
  - [x] `worker/SendDispatcher.kt`, inside `dispatchDueSlots(...)`'s per-schedule-time loop: replace the "select a list → enqueue each" shape with "select at most one → enqueue it if present":
    ```kotlin
    val image = when (
        val result = imageSelectionEngine.selectImageFor(receiver.id)
    ) {
        is AppResult.Success -> result.value ?: continue // no active images — nothing due to send this slot
        is AppResult.Failure -> continue
    }

    val enqueued = when (
        val result = transmissionRepository.enqueue(receiver.id, image.id, scheduleTime, nowInstant)
    ) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> continue
    }
    attemptDelivery(receiver, image, enqueued)
    ```
    replacing the existing `val selected = ... imageSelectionEngine.selectImagesFor(receiver.id, receiver.minCount, receiver.maxCount) ...` block and its enclosing `for (image in selected) { ... }` loop. The body of the old loop (enqueue + `attemptDelivery`) is unchanged in substance, just no longer wrapped in a `for`.
  - [x] **Do not touch** `retryPendingItems(...)`, `attemptDelivery(...)`, the master-schedule fallback (`receiverWithSchedules.scheduleTimes.ifEmpty { masterScheduleTimes }`), or anything about "already dispatched today"/due-time checks — this task only changes what happens once a slot is confirmed due and not yet dispatched.

- [x] Task 4: Remove Min/Max images fields from the receiver form (AC: 2)
  - [x] `ui/receivers/ReceiverEditScreen.kt`: remove `minCountText`/`maxCountText` (`remember { mutableStateOf(...) }` state) and `countError` (state + its `Text` display) entirely. Remove the `Row` containing the "Min images"/"Max images" `OutlinedTextField`s (the whole `Column(verticalArrangement = ...) { Row { ... } countError?.let { ... } }` block, directly above the `ScheduleTimeListEditor(...)` call).
  - [x] In the Save `onClick` block: remove the `val min = minCountText.toIntOrNull()` / `val max = maxCountText.toIntOrNull()` locals and the `countError = when { ... }` validation block. Update the combined early-return guard from `if (nameError != null || contactError != null || countError != null || scheduleError != null)` to `if (nameError != null || contactError != null || scheduleError != null)`. Remove `minCount = min!!, maxCount = max!!` from the `Receiver(...)` construction — it now only takes `id`/`name`/`channel`/`phoneOrEmail`.
  - [x] Class-level doc comment: the line "Each independently rolls a random image count within the receiver's min/max at send time (mechanics.md's existing per-send algorithm, unchanged); this form just manages the list of times." is now **factually wrong** (that algorithm no longer exists) — remove it. The rest of the doc comment (channel segmented control, `isNew` derivation, optional-schedule note) is unaffected and stays.
  - [x] **Do not touch** `nameError`/`contactError`/`scheduleError` validation, the `TimePickerDialog`/duplicate-time handling, or the save call's success/failure handling — only the count fields and their validation are removed.

- [x] Task 5: Remove the image-count range from the receiver list row (AC: 2)
  - [x] `ui/receivers/ReceiversScreen.kt`: change the schedule-summary `Text` from `text = "$scheduleSummary · %d–%d images".format(receiver.minCount, receiver.maxCount)` to `text = scheduleSummary` — the row now shows only the schedule summary (e.g. "4×/day" or "Uses master schedule"), no count range. **Do not touch** the "Needs N more schedule time(s)" warning logic, the swipe-to-delete/`AlertDialog` flow, or anything else in `ReceiverRow`.

- [x] Task 6: Doc-consistency fix — stale "count range" references (AC: 2, incidental)
  - [x] `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/DESIGN.md` line ~103 (`Receiver row` component description): remove `+ count range` from `"...schedule summary (e.g. "4×/day", or "Uses master schedule" if the receiver has none of its own) + count range as {typography.label} meta text below..."` — the earlier correct-course pass (2026-07-13) updated `SPEC.md`/`mechanics.md`/`epics.md` for single-image selection but missed this line; it now describes a UI element this story removes. Leave everything else in that bullet (the "tap opens edit; trailing icon-button for delete" clause, etc.) unchanged.
  - [x] `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/EXPERIENCE.md` line ~29 (Information Architecture table, "Receiver Edit" row): remove `, count range` from `"One receiver's fields: name, channel, contact, count range, schedule times (optional; ...)"`. Same root cause as the DESIGN.md fix above — a small, mechanical text edit, not a design decision.
  - [x] This is a two-line documentation cleanup, not a design change — do not use it as license to touch anything else in either file.

- [x] Task 7: Tests (AC: 1, 2, 3)
  - [x] `app/src/test/java/com/ris/imagedistributor/domain/ImageSelectionEngineTest.kt` — rewrite for the new `selectImageFor(receiverId)` signature. Cases: returns the only eligible image when the eligible pool has exactly one; returns an image from the eligible pool when it has several (assert the result's id is a member of the eligible set, using an injected fixed-seed `Random` for determinism — mirror the existing `re-rolls independently across calls`-style seeded-`Random` pattern); falls back to a repeat from the full active pool when the eligible (not-recently-sent) pool is empty (assert the result's id is a member of the *active* set, and construct the scenario so it's **not** a member of the eligible set, proving the fallback actually happened rather than coincidentally picking an eligible one); returns `AppResult.Success(null)` when there are zero active images, **and** verifies `transmissionRepository.getRecentlySentImageIds(...)` is never called in that case (`coVerify(exactly = 0)`) — proving Task 2's short-circuit; propagates a `Failure` from `getActiveImages`; propagates a `Failure` from `getRecentlySentImageIds`; does not swallow `CancellationException` from `getActiveImages`; queries transmissions since approximately 7 days before now (keep this test, adapted to the new signature — no more `minCount`/`maxCount` args to pass). Remove every test whose entire premise no longer applies (`rejects minCount greater than maxCount`, `rejects a negative minCount`, `rejects maxCount of Int MAX_VALUE`, `never draws Z outside the requested min-max bounds`, `re-rolls the random count independently across calls`, `sends only the available active images when the total active count is smaller than Z`) — there is no `Z`/count bound left to test.
  - [x] `app/src/test/java/com/ris/imagedistributor/worker/SendDispatcherTest.kt` — update every `Receiver(...)` construction to drop `minCount =`/`maxCount =` (the class no longer has those fields — a compile error otherwise). Update every `imageSelectionEngine.selectImagesFor(...)` mock/verify call to `imageSelectionEngine.selectImageFor(receiver.id)` (or `selectImageFor(any())` where the existing test used `any(), any(), any()`), returning `AppResult.Success(image)` in the existing "dispatches" cases. Add one new case: `selectImageFor` returning `AppResult.Success(null)` results in nothing enqueued and `deliveryRepository.send(...)` never called for that slot (no crash, no exception) — the direct test for this story's "sending nothing if there are no active images at all" AC.
  - [x] Mechanical constructor-argument cleanup only (no behavior change, these files don't assert on the count fields) — remove `minCount =`/`maxCount =` from every `Receiver(...)` construction in: `app/src/androidTest/java/com/ris/imagedistributor/data/repository/ReceiverRepositoryImplTest.kt`, `app/src/androidTest/java/com/ris/imagedistributor/ui/DashboardScreenTest.kt`, `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt`, `app/src/test/java/com/ris/imagedistributor/data/repository/DeliveryRepositoryImplTest.kt`, `app/src/test/java/com/ris/imagedistributor/ui/dashboard/DashboardViewModelTest.kt`, `app/src/test/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModelTest.kt`. All of these will otherwise fail to compile once Task 1 removes the fields.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` — add `migrate9To10_dropsMinMaxCountColumnsFromReceivers()`: seed a v9 `receivers` row with `minCount`/`maxCount` populated (plus `name`/`channel`/`phoneOrEmail`), run `MIGRATION_9_10`, then query `SELECT * FROM receivers` and assert via the returned `Cursor.getColumnNames()` that `minCount`/`maxCount` are **absent** and `id`/`name`/`channel`/`phoneOrEmail` are **present**, and that the row's `name`/`channel`/`phoneOrEmail` values survived unchanged. Also seed a `receiver_schedules` row for the same receiver id beforehand and confirm it's still queryable/correctly linked after the migration (proving the FK-referenced id was preserved by the copy-and-rename). Extend the existing full-chain test (`migrate1Through9...`) to `migrate1Through10...`, adding `MIGRATION_9_10` to its migration list.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (or wherever `ReceiverEditScreen` is exercised — check for an existing dedicated file first, per this codebase's established "extend, don't duplicate" convention) — add a case confirming `onNodeWithText("Min images")` and `onNodeWithText("Max images")` do not exist on the Add/Edit form, and a case confirming Save succeeds for a new receiver with only name/channel/contact filled in (no count fields to satisfy). Also confirm the list row for a receiver no longer displays an "images" count-range suffix (e.g. assert the row's meta text is exactly the schedule summary, not `<summary> · N–M images`).
  - [x] Full regression pass: `gradle :app:testDebugUnitTest` and `gradle :app:connectedDebugAndroidTest`, both verified via their XML results (not just "BUILD SUCCESSFUL") — confirm pre-existing test counts shrink only where this story explicitly removed now-inapplicable tests (documented above) and otherwise only grow, with 0 failures/errors.

- [x] Task 8: Live on-device verification (AC: 1, 2, 3)
  - [x] `adb uninstall` then fresh `installDebug` (a version-10 migration with a destructive table-recreate benefits from a clean-install verification pass, same established convention as every prior schema-changing story).
  - [x] Add a receiver — confirm no "Min images"/"Max images" fields appear anywhere on the form, and Save succeeds with just name/channel/contact/schedule. Confirm the Receivers list row shows only the schedule summary (e.g. "4×/day" or "Uses master schedule"), no count range.
  - [x] Upload at least 1 active image (Images tab). If feasible within the emulator's clock (via `adb shell date` or waiting, per this session's established technique), advance time past one of a receiver's due schedule times and confirm via the Dashboard (Story 3.1) that **exactly one** transmission was created for that slot — not a random batch. If driving an actual live delivery proves impractical in this environment (a recurring, already-documented limitation in this session — see Story 2.3's own Completion Notes), it is acceptable to rely on Task 7's exhaustive `SendDispatcherTest`/`ImageSelectionEngineTest` coverage for AC1's dispatch-selection logic instead, same disclosed trade-off Story 2.3 made for its own AC2 — state explicitly in Completion Notes which path was taken and why.

### Review Findings

- [x] [Review][Patch] `ReceiversScreenTest.addReceiverDoesNotShowScheduleErrorWhenScheduleIsEmpty` doesn't assert the receiver was actually saved — it only checks the schedule-time error text is absent after tapping Save, never that `repository.addReceiver` was invoked or that `onDone` fired. Weaker than the newer `addReceiverSucceedsWithOnlyNameChannelAndContactFilled` test added later in the same file, which does check `onDone`. [`app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt`] — fixed: the test now fills in Name/Phone too (previously it left them blank, which would have silently blocked Save via unrelated validation without the test noticing), stubs and verifies `repository.addReceiver(any(), emptyList())`, and asserts `onDone` fires. Re-verified via the full regression suite (151 unit + 66 instrumented, both green, verified via XML).
- [x] [Review][Defer] A receiver with no schedule of its own AND an empty/unconfigured master schedule shows "Uses master schedule" in the list with no warning that it will never actually receive anything — the underlying `isNotEmpty() && size < MIN_SCHEDULE_TIMES` warning condition doesn't catch this combination. [`app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt`] — deferred, pre-existing: this diff only removed the count-range text suffix from the row; the warning condition itself is unchanged code from Story 2.3, out of scope for Story 2.4's own review.

## Dev Notes

- **This story's core mechanism is small and precise, matching Story 2.3's own Dev Notes framing: resist building anything more elaborate than the 4-step algorithm in `mechanics.md#Selection algorithm (CAP-3)`.** No count ranges, no batch logic, no per-receiver configuration of "how many" — every scheduled send is exactly one image or nothing. If the implementation grows more complex than Tasks 2–3 describe, stop and re-read `mechanics.md`'s Selection algorithm section before continuing.
- **`selectImageFor` returning `AppResult<Image?>` (a nullable single image) rather than `AppResult<List<Image>>` (an always-0-or-1-sized list) is a deliberate API design choice, not an arbitrary rename.** A list that's merely "guaranteed to be size 0 or 1 by convention" is a leaky abstraction inviting a future regression back toward batch semantics; a nullable single value makes "at most one" the type itself, not a documented invariant someone has to remember. This directly matches `sprint-change-proposal-2026-07-13.md`'s own description of the required `SendDispatcher` change: "select at most one image → enqueue it if present."
- **The 7-day-exclusion / repeat-fallback logic is unchanged in kind — only the "how many" (`Z`) mechanism disappears.** Steps 1–3 of the old algorithm (build eligible pool, prefer it, widen to full active pool if exhausted) survive essentially verbatim; only "pick `Z` via `shuffled(random).take(z)`" becomes "pick 1 via `random(random)`" (single-element pick, not a shuffle+take of size 1 — simpler and equally random for a single draw).
- **`Receiver.minCount`/`maxCount` removal is destructive by design (AC3) — do not add any fallback/default/preserved-value logic for the dropped columns.** This mirrors `MIGRATION_3_4`'s own precedent (a destructive-but-intentional schema change, explicitly reasoned about and accepted, not an oversight) rather than `AD-15`'s "no destructive fallback" rule — that rule governs *data loss the operator didn't intend*; here, the operator/spec explicitly intend to discard these columns because the concept they represented no longer exists in the product.
- **`receiver_schedules` and its `receiverId` foreign key are completely unaffected by `MIGRATION_9_10`.** The copy-and-rename preserves every `receivers.id` value exactly (same `SELECT id, ... FROM receivers` copy order `MIGRATION_3_4` already established), so existing schedule rows keep resolving to the correct receiver with zero changes needed on that side. Do not add any `receiver_schedules`-touching SQL to this migration.
- **This story does not touch Story 2.3's master-schedule fallback mechanism at all.** `SendDispatcher.dispatchDueSlots`'s `receiverWithSchedules.scheduleTimes.ifEmpty { masterScheduleTimes }` line stays exactly as Story 2.3 left it — Task 3 only changes what happens *after* a schedule time is confirmed due, not which schedule times are considered due in the first place. These are two independent, previously-shipped mechanisms both still in play; don't conflate them or "helpfully" refactor their interaction.
- **Task 6 (the two-line DESIGN.md/EXPERIENCE.md fix) exists because the 2026-07-13 correct-course pass updated `SPEC.md`/`mechanics.md`/`epics.md` for single-image selection but missed these two UX-doc lines.** It's included here because this story is the one that actually removes the field those lines describe — leaving the docs stale past this point would be a new, avoidable inconsistency, not a pre-existing one worth deferring. Keep this fix minimal and mechanical (two short phrase deletions); do not use it as an opening to revise anything else in either document.

### Previous Story Intelligence (from Story 2.3 and this session's Story 1.4)

- **`MIGRATION_3_4` (in `AppDatabase.kt`, from Story 1.3) is the exact template for this story's `MIGRATION_9_10`** — read it before writing the new migration; copy its create-new-table → copy-matching-columns → drop-old → rename-new shape precisely rather than approximating it from memory.
- **`adb uninstall` before `installDebug` remains mandatory for any schema-changing story** in this session's established live-verification convention — this story bumps the DB version to 10 via a destructive recreate, making a clean-install pass especially worth doing precisely (confirm the fresh-install path, which never runs any `Migration` at all per Room's own behavior, produces a correct 4-column `receivers` table straight from the `@Entity` annotation).
- **`uiautomator dump` + parsing `bounds="[x1,y1][x2,y2]"` remains the reliable way to get exact tap coordinates for live verification** — visual screenshot estimation has repeatedly proven unreliable across every story this session that attempted it.
- **This session's toolchain remains**: `C:\Android\gradle\gradle-9.4.1` (via `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`), `C:\Android\sdk`'s `platform-tools`/`emulator`, `imagedrop_test(AVD) - 15` — no Gradle wrapper or Android Studio installation exists in this shell environment.
- **Story 2.3's own Completion Notes disclosed that driving an actual live delivery through the system photo picker proved unreliable in this environment** (empty Image Library on a fresh install, flaky `adb shell input` interaction with the picker) — if Task 8 hits the same wall, exhaustive unit-test coverage (Task 7) is an already-established, accepted substitute for that one specific sub-verification; state the trade-off explicitly rather than silently skipping it.
- **This codebase's established "extend an existing test file, don't duplicate" convention applies throughout Task 7** — check for an existing file covering the relevant screen/class before creating a new one, exactly as every prior story in this session has done.

## Dev Agent Record

### Context Reference

<!-- Path(s) to story context XML/JSON will be added here by context workflow -->

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- `gradle :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` — first pass surfaced the exact expected compile errors across all 15 files that referenced `minCount`/`maxCount`/`selectImagesFor` (used deliberately as a completeness check rather than pre-emptively guessing every call site); all fixed, second pass BUILD SUCCESSFUL.
- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL, verified via XML: 151 tests, 0 failures, 0 errors (was 156 before this story — net -5, entirely accounted for: 6 now-inapplicable `ImageSelectionEngineTest` cases removed per Task 7's own instructions, 1 new `SendDispatcherTest` case added).
- `gradle :app:connectedDebugAndroidTest` (against `imagedrop_test(AVD) - 15`) — BUILD SUCCESSFUL, verified via XML: 66 tests, 0 failures, 0 errors (was 63 before this story — +3: `migrate9To10_dropsMinMaxCountColumnsFromReceivers`, plus 2 new `ReceiversScreenTest` cases).
- Live on-device verification: `adb uninstall` (harmless `DELETE_FAILED_INTERNAL_ERROR` — confirmed via the subsequent Setup-screen requirement that the app genuinely wasn't installed, same benign pattern seen in every prior story this session) → fresh `installDebug` → full Setup flow → confirmed AC2/AC3 live (see Completion Notes). Attempted AC1's live dispatch via `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.ris.imagedistributor <jobId>` to force-trigger `SendWorker` immediately rather than waiting on WorkManager's own interval — confirmed via logcat (`WM-WorkerWrapper: Starting work for ... SendWorker` → `Worker result SUCCESS`) that it ran, but the Dashboard showed no *sent* transmission for the test receiver, consistent with this environment having no real WhatsApp Business API to deliver to (a `Transmission` row can exist as `PENDING`/`FAILED` without ever showing as sent) — not a code defect, the same category of environment limitation Story 2.3 already documented for its own AC2.
- Same toolchain as every other story this session: `C:\Android\gradle\gradle-9.4.1` via `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, `C:\Android\sdk`'s `platform-tools`/`emulator` (no Gradle wrapper or Android Studio install present in this shell environment).

### Completion Notes List

- All 8 tasks implemented and covered by tests: `ImageSelectionEngineTest` rewritten from 14 to 8 cases for the new `selectImageFor(receiverId): AppResult<Image?>` signature; `SendDispatcherTest` updated throughout plus 1 new case (`null` selection → nothing enqueued, no delivery attempt); `AppDatabaseMigrationTest` +2 (`migrate9To10...`, full-chain extended to `migrate1Through10...`); `ReceiversScreenTest` +2 new cases (no Min/Max fields; Save succeeds without them) plus assertion updates on 2 existing cases whose expected row text changed; 6 other test files mechanically updated (constructor-argument removal only, no behavior change, per Task 7's own scoping). 151 unit tests total (was 156, net -5 exactly as documented in Task 7), 66 instrumented tests total (was 63, net +3), all passing, verified via XML both times.
- **API design decision, not just a rename**: `ImageSelectionEngine.selectImageFor(receiverId: Long): AppResult<Image?>` replaces `selectImagesFor(receiverId, minCount, maxCount): AppResult<List<Image>>`. A nullable single `Image` (rather than an always-0-or-1-sized `List<Image>`) makes "at most one" a property of the type itself rather than an unenforced convention — matches `sprint-change-proposal-2026-07-13.md`'s own framing ("select at most one image → enqueue it if present") and was called out explicitly in the story's own Dev Notes as the intended approach, not something improvised during implementation.
- **The `active.isEmpty()` short-circuit (checking before querying `transmissionRepository.getRecentlySentImageIds`) was implemented exactly as the story specified**, and is directly tested (`coVerify(exactly = 0)` on that call in the zero-active-images case) — this is a deliberate query-avoidance optimization, not a spec deviation, per the story's own Dev Notes.
- **`MIGRATION_9_10` mirrors `MIGRATION_3_4`'s copy-and-rename shape exactly**, as instructed — read `MIGRATION_3_4` before writing it rather than approximating from memory. The dedicated migration test additionally seeds a `receiver_schedules` row beforehand and confirms it still resolves correctly afterward, directly proving the FK-referenced `id` values survive the recreate (not just asserted in a code comment).
- **Live on-device verification, explicit, per AC:**
  - **AC2** (no min/max fields on the receiver form): confirmed the Add Receiver form shows only Name / WhatsApp-Email toggle / Phone / Schedule times / Save — no "Min images"/"Max images" fields anywhere. Saved a new receiver ("Priya") with just name + phone + zero schedule times — Save succeeded immediately, no count fields to satisfy, and the list row read exactly "Priya · WhatsApp" / "Uses master schedule", with no image-count-range suffix, confirming both the form removal and the list-row summary fix live.
  - **AC3** (migration drops min/max entirely): proven primarily via the dedicated `migrate9To10_dropsMinMaxCountColumnsFromReceivers` instrumented test (asserts the columns are actually absent from the recreated table's `Cursor.getColumnNames()`, not just "the app didn't crash") — a stronger verification than eyeballing a live install, since a live install alone can't distinguish "columns dropped" from "columns present but unused." The live install itself (fresh, version-10 schema) ran without any migration-related crash throughout the entire verification session.
  - **AC1** (single-image selection): exhaustively covered at the unit/integration level (8 `ImageSelectionEngineTest` cases covering the full 4-step algorithm including the repeat-fallback and zero-active-images paths, plus dedicated `SendDispatcherTest` cases proving exactly one image gets enqueued per dispatched slot and that a `null` selection results in nothing enqueued/no delivery attempt). Live verification confirmed `SendWorker` executes without crashing against the new single-image dispatch path (force-triggered via `adb shell cmd jobscheduler run`, confirmed via logcat), but did not confirm an actual *delivered* transmission on the Dashboard — this environment has no real WhatsApp Business API to deliver to, so a `Transmission` row can be created and attempted without ever reaching `SENT` status, which the Dashboard filters to. Same disclosed trade-off Story 2.3 made for its own AC2; the story's own Task 8 text explicitly anticipated and permitted this fallback.
  - Zero crashes throughout the entire verification session, confirmed via a final `logcat | grep "FATAL EXCEPTION"` sweep (empty result).

### File List

**New:**
- `app/src/test/java/com/ris/imagedistributor/worker` — no new files (existing `SendDispatcherTest.kt` extended)

**Updated:**
- `app/src/main/java/com/ris/imagedistributor/data/local/Receiver.kt` (removed `minCount`/`maxCount`)
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (version 9→10, `MIGRATION_9_10`; `MIGRATION_1_2`–`MIGRATION_8_9` untouched)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (registered `MIGRATION_9_10`)
- `app/src/main/java/com/ris/imagedistributor/domain/ImageSelectionEngine.kt` (`selectImagesFor(receiverId, minCount, maxCount)` replaced with `selectImageFor(receiverId): AppResult<Image?>`, simplified to the 4-step single-image algorithm)
- `app/src/main/java/com/ris/imagedistributor/worker/SendDispatcher.kt` (`dispatchDueSlots` updated to select-at-most-one/enqueue-if-present instead of a list-iterating loop)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiverEditScreen.kt` (removed Min/Max images fields, `countError` state/validation, and the now-inaccurate doc-comment sentence about per-send random image counts)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt` (row summary no longer appends an image-count range)
- `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/DESIGN.md` (removed stale "+ count range" phrase from the Receiver row component description)
- `_bmad-output/planning-artifacts/ux-designs/ux-RIS-2026-07-09/EXPERIENCE.md` (removed stale ", count range" phrase from the Receiver Edit IA row)
- `app/src/test/java/com/ris/imagedistributor/domain/ImageSelectionEngineTest.kt` (rewritten for the new `selectImageFor` signature; 14→8 cases)
- `app/src/test/java/com/ris/imagedistributor/worker/SendDispatcherTest.kt` (all `selectImagesFor`/`Receiver(...)` references updated; +1 new null-selection case)
- `app/src/test/java/com/ris/imagedistributor/data/repository/DeliveryRepositoryImplTest.kt` (mechanical: `minCount`/`maxCount` removed from `Receiver(...)`)
- `app/src/test/java/com/ris/imagedistributor/ui/dashboard/DashboardViewModelTest.kt` (mechanical: same)
- `app/src/test/java/com/ris/imagedistributor/ui/receivers/ReceiversViewModelTest.kt` (mechanical: same)
- `app/src/androidTest/java/com/ris/imagedistributor/data/repository/ReceiverRepositoryImplTest.kt` (mechanical: same)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/DashboardScreenTest.kt` (mechanical: same)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (mechanical constructor cleanup + 2 existing assertions updated for the removed image-count suffix + 2 new cases confirming the Min/Max fields are gone and Save succeeds without them)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (added `migrate9To10_dropsMinMaxCountColumnsFromReceivers`; full-chain test extended to `migrate1Through10...`)

## Change Log

- 2026-07-13: Story implemented end-to-end (Tasks 1–8). Dropped `Receiver.minCount`/`maxCount` via a destructive copy-and-rename migration (9→10, mirroring `MIGRATION_3_4`'s precedent), simplified `ImageSelectionEngine` to a single-image `selectImageFor(receiverId): AppResult<Image?>` API implementing the 4-step algorithm from `mechanics.md` (eligible pool → prefer it → repeat-fallback → null if zero active images), updated `SendDispatcher`'s dispatch loop to select-at-most-one/enqueue-if-present, removed the Min/Max images fields from the receiver form and the image-count range from the receiver list row, and fixed two stale "count range" references in DESIGN.md/EXPERIENCE.md left over from an earlier correct-course pass. 151 unit tests + 66 instrumented tests, all passing, verified via XML (net -5/+3 respectively, fully accounted for by intentionally-removed now-inapplicable tests and newly-added coverage). Live-emulator verification confirmed AC2 and AC3 end-to-end (no min/max fields on the form, list row shows only the schedule summary, a receiver saves successfully with zero fields to satisfy for count). AC1's dispatch-selection logic is exhaustively unit/integration-tested; live delivery confirmation was not possible in this environment (no real WhatsApp Business API to deliver to), matching the same disclosed trade-off Story 2.3 made for its own AC2 — `SendWorker` was confirmed running successfully against the new code path via a force-triggered WorkManager job and logcat, with zero crashes throughout the entire verification session. Status moved to `review`.
- 2026-07-13: Code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor, `review_mode=full` against this story's own ACs). Acceptance Auditor found zero violations across all 3 ACs. 1 patch finding applied: `addReceiverDoesNotShowScheduleErrorWhenScheduleIsEmpty` now actually fills in Name/Phone and verifies `repository.addReceiver`/`onDone` fire, instead of only checking the schedule-error text was absent (it was previously possible for this test to pass even if Save was silently blocked by unrelated validation). 1 finding deferred as pre-existing/out-of-scope (a schedule-less receiver with an also-empty master schedule shows no warning — unchanged code from Story 2.3). 14 findings dismissed after direct verification against the code — notably the foreign-key/`DROP TABLE`-ordering concern was empirically disproven by the migration's own passing test (which seeds an FK-referencing row and succeeds), and the "no other `minCount`/`maxCount` read sites" concern was checked with a full-codebase grep that came back clean. 151 unit + 66 instrumented tests, all passing, verified via XML. Status moved to `done`.
