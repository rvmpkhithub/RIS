---
baseline_commit: NO_VCS
---

# Story 2.3: Master Schedule Fallback

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want to set one app-wide default schedule that receivers use automatically when I haven't given them their own,
so that I'm not forced to configure a schedule for every single receiver.

## Acceptance Criteria

1. Given I'm in Settings, When I configure the master schedule with one or more daily times (minimum 4), Then it's saved and used by every receiver that has no schedule of its own. [Source: epics.md#Story 2.3] [Source: sprint-change-proposal-2026-07-12.md#4. Detailed Change Proposals]
2. Given a receiver has no schedule times of its own, When a scheduled-send check runs for that receiver, Then it uses the master schedule's times instead, evaluated fresh each time (not copied onto the receiver). [Source: epics.md#Story 2.3] [Source: ARCHITECTURE-SPINE.md#AD-16]
3. Given I change the master schedule, When the new value is saved, Then it takes effect from the next scheduled-send check onward, for every receiver still relying on it. [Source: epics.md#Story 2.3] [Source: ARCHITECTURE-SPINE.md#AD-16]
4. Given a receiver's own schedule is being edited, When it has between 1 and 3 times (partially filled, not empty, not at the minimum), Then Save is still blocked with the existing "Add at least 4 schedule times" error — only a genuinely empty list is now valid. [Source: sprint-change-proposal-2026-07-12.md#4. Detailed Change Proposals] [Source: EXPERIENCE.md#Component Patterns — Schedule time list]

**Scope boundary:** this story adds the `MasterSchedule` entity/DAO/repository, a new Settings UI section to edit it, `SendDispatcher`'s fallback branch, and relaxes `ReceiverEditScreen`'s validation from "always ≥4" to "0, or ≥4" (a targeted amendment to Story 1.3's shipped validation, landing here because it is inseparable from this story's own fallback logic — see `sprint-change-proposal-2026-07-12.md#5. Implementation Handoff`). It does **not** change `ReceiverSchedule`'s schema, `Receiver`'s schema, or anything about how `SendDispatcher` selects images or delivers them (Story 2.1/2.2 logic is untouched) — only which list of times it iterates per receiver.

## Tasks / Subtasks

- [x] Task 1: `MasterSchedule` entity, DAO, and migration 7→8 (AC: 1, 2, 3)
  - [x] `data/local/MasterSchedule.kt` (new file) — `@Entity(tableName = "master_schedule") data class MasterSchedule(@PrimaryKey(autoGenerate = true) val id: Long = 0, val time: Int)`. No `ForeignKey`, no `receiverId` — this is deliberately app-wide, not tied to any receiver (AD-16). Same `Int` minutes-since-midnight convention as `ReceiverSchedule.time`.
  - [x] `data/local/MasterScheduleDao.kt` (new file):
    - `@Query("SELECT * FROM master_schedule ORDER BY time ASC") fun observeAll(): Flow<List<MasterSchedule>>` — for the Settings screen's live editor.
    - `@Query("SELECT * FROM master_schedule ORDER BY time ASC") suspend fun getAllOnce(): List<MasterSchedule>` — one-shot snapshot for `SendDispatcher`'s dispatch tick, mirroring `ReceiverDao.getAllWithSchedules()`'s existing one-shot-twin-of-a-Flow pattern (Story 2.2 precedent).
    - `@Insert suspend fun insert(schedule: MasterSchedule): Long`
    - `@Query("DELETE FROM master_schedule") suspend fun deleteAll()`
  - [x] `data/local/AppDatabase.kt`: add `MasterSchedule::class` to the `entities` array, bump `version` to `8`, add `MIGRATION_7_8`:
    ```kotlin
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `master_schedule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`time` INTEGER NOT NULL)"
            )
            listOf(540, 720, 900, 1080).forEach { time -> // 09:00, 12:00, 15:00, 18:00
                db.execSQL("INSERT INTO `master_schedule` (`time`) VALUES ($time)")
            }
        }
    }
    ```
    This mirrors `MIGRATION_6_7`'s exact shape (create table + seed a default at migration time) for an app **upgrading** from an existing v7 database. **Do not touch `MIGRATION_1_2` through `MIGRATION_6_7`.**
  - [x] **Critical gotcha — do not rely on the migration alone.** `ARCHITECTURE-SPINE.md`'s Consistency Conventions table says `MasterSchedule` rows are "seeded at DB creation," but — exactly as `RetentionSetting.kt`'s own doc comment already spells out for the identical situation — a genuinely **fresh install never runs any `Migration`** (Room creates the schema straight from the `@Entity`/`@Database` annotations, skipping every `MIGRATION_X_Y` entirely). If `MasterScheduleRepositoryImpl` (Task 2) only ever reads whatever rows exist, a fresh install would see an empty table and no fallback for schedule-less receivers to fall back to. Task 2's repository-level default handles this the same way `RetentionRepositoryImpl.getRetentionDays()`/`observeRetentionDays()` already do for `RetentionSetting` — do not add a `RoomDatabase.Callback` for this; it would be a new, unprecedented seeding mechanism in a codebase that has consistently solved this exact problem at the repository layer instead.

- [x] Task 2: `MasterScheduleRepository`/`Impl` (AC: 1, 3)
  - [x] `data/repository/MasterScheduleRepository.kt` (new file):
    ```kotlin
    interface MasterScheduleRepository {
        fun observeScheduleTimes(): Flow<List<Int>>
        suspend fun getScheduleTimes(): AppResult<List<Int>>
        suspend fun setScheduleTimes(times: List<Int>): AppResult<Unit>
    }
    ```
  - [x] `data/repository/MasterScheduleRepositoryImpl.kt` (new file), same `runCatchingDb` pattern as every other repository in this codebase:
    - `observeScheduleTimes()`: `dao.observeAll().map { list -> list.map { it.time } }.catch { e -> if (e is CancellationException) throw e else emit(DEFAULT_MASTER_SCHEDULE_TIMES) }` — falls back to the same 4 defaults as the migration seed, for the fresh-install case described in Task 1's gotcha (mirrors `RetentionRepositoryImpl.observeRetentionDays()`'s `DEFAULT_RETENTION_DAYS` fallback exactly).
    - `getScheduleTimes()`: `runCatchingDb { dao.getAllOnce().map { it.time }.ifEmpty { DEFAULT_MASTER_SCHEDULE_TIMES } }` — same defensive default, for `SendDispatcher`'s one-shot read.
    - `setScheduleTimes(times: List<Int>)`: validates `times.size >= MIN_SCHEDULE_TIMES` unconditionally first (**no** "or zero" exception here — unlike a receiver's own schedule, there is no fallback for the master schedule itself to fall back to; `ARCHITECTURE-SPINE.md`'s Consistency Conventions table is explicit that at least 4 rows always exist). Return `AppResult.Failure(FailureReason.INVALID_INPUT)` if not. Otherwise, inside `database.withTransaction { dao.deleteAll(); times.forEach { time -> dao.insert(MasterSchedule(time = time)) } }` — same delete-all-then-reinsert whole-list-replace pattern as `ReceiverRepositoryImpl.updateReceiver()`'s schedule handling (Story 1.3 precedent), for the same reason: this form is only ever saved as a whole, never partially.
    - `companion object { const val DEFAULT_MASTER_SCHEDULE_TIMES = listOf(540, 720, 900, 1080) }` — same four times as the migration seed (keep these two lists in sync; consider referencing one constant from the other if that doesn't create a data-layer → data-layer awkward import, otherwise duplicate the literal with a comment cross-referencing `MIGRATION_7_8`).
    - This repository needs `database: AppDatabase` (for `withTransaction`), not just the DAO — same constructor shape as `ReceiverRepositoryImpl`, not the plain-DAO shape `RetentionRepositoryImpl` uses, because `setScheduleTimes` needs transactional delete+reinsert across what could be several `insert` calls.

- [x] Task 3: Wire `MasterScheduleRepository` into `AppContainer` (AC: 1, 2, 3)
  - [x] `di/AppContainer.kt`: add `AppDatabase.MIGRATION_7_8` to the `.addMigrations(...)` list (alongside the existing 6 migrations — **do not touch any existing migration registration**). Add a new lazy property:
    ```kotlin
    val masterScheduleRepository: MasterScheduleRepository by lazy {
        MasterScheduleRepositoryImpl(database = database)
    }
    ```
    placed near `retentionRepository`/`receiverRepository` (same section of the file). Pass `masterScheduleRepository` into `sendDispatcher`'s constructor call (Task 4 adds the parameter) and into `SettingsViewModel.factory(container)` (Task 6).

- [x] Task 4: `SendDispatcher`'s fallback branch (AC: 2, 3)
  - [x] `worker/SendDispatcher.kt`: add `private val masterScheduleRepository: MasterScheduleRepository` to the constructor (after `complianceGate`, before the injectable `now`).
  - [x] In `dispatchDueSends()`, after loading `receivers` and before calling `dispatchDueSlots(...)`, fetch the master schedule **once per run** (not once per receiver — it's app-wide, and a run may dispatch for many schedule-less receivers; fetching it once keeps this efficient and still correct, since AD-16 only requires it be queried live *per run*, not per receiver):
    ```kotlin
    val masterScheduleTimes = when (val result = masterScheduleRepository.getScheduleTimes()) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> emptyList() // fail-safe: a schedule-less receiver simply has nothing due this run, not a crash
    }
    ```
    Pass `masterScheduleTimes` into `dispatchDueSlots(receivers, nowInstant, todayStart, zone, masterScheduleTimes)`.
  - [x] In `dispatchDueSlots(...)`, change the per-receiver inner loop from unconditionally iterating `receiverWithSchedules.scheduleTimes` to:
    ```kotlin
    val effectiveScheduleTimes = receiverWithSchedules.scheduleTimes.ifEmpty { masterScheduleTimes }
    for (scheduleTime in effectiveScheduleTimes) { ... }
    ```
    (replacing the existing `for (scheduleTime in receiverWithSchedules.scheduleTimes)` line). Everything downstream of that loop — the already-dispatched check, image selection, enqueue, delivery — is completely unchanged; it only ever sees a plain `Int` schedule time either way and has no idea whether it came from the receiver's own rows or the master schedule. **This is the entire fallback mechanism** — no other branch, no merge, no partial-fallback logic. A receiver with even one schedule time of its own never touches `masterScheduleTimes` at all, per AD-16 ("even partially").
  - [x] **Do not touch** `retryPendingItems(...)` or `attemptDelivery(...)` — the fallback only affects which slots are considered *due*, not anything about retries or delivery of already-enqueued items.

- [x] Task 5: Relax `ReceiverEditScreen`'s schedule validation (AC: 4)
  - [x] `ui/receivers/ReceiverEditScreen.kt` line ~287 — change:
    ```kotlin
    scheduleError = if (scheduleTimes.size < MIN_SCHEDULE_TIMES) {
        "Add at least $MIN_SCHEDULE_TIMES schedule times."
    } else {
        null
    }
    ```
    to:
    ```kotlin
    scheduleError = if (scheduleTimes.isNotEmpty() && scheduleTimes.size < MIN_SCHEDULE_TIMES) {
        "Add at least $MIN_SCHEDULE_TIMES schedule times."
    } else {
        null
    }
    ```
    Zero times is now valid (falls back to the master schedule at dispatch time); 1–3 times is still invalid (AC4) — only the `scheduleTimes.isEmpty()` case changes from invalid to valid. `MIN_SCHEDULE_TIMES` itself (line 45) stays `4`, unchanged.
  - [x] Update the class doc comment (lines 57–60) — it currently cites "[Sprint Change Proposal 2026-07-10]" for the "one or more daily schedule times, minimum 4" rule, which this story supersedes. Update the citation to reference `sprint-change-proposal-2026-07-12.md` and the new optional-schedule rule, so a future reader isn't pointed at a now-superseded proposal.
  - [x] **Do not touch** any other validation (`nameError`/`contactError`/`countError`), the `TimePickerDialog`/duplicate-time handling, or the save call itself — an empty `scheduleTimes` list already passes through `viewModel.save(...)` → `ReceiverRepositoryImpl.addReceiver()`/`updateReceiver()` unchanged, since those methods' `scheduleTimes.forEach { ... }` loops already handle an empty list correctly (confirmed by reading `ReceiverRepositoryImpl.kt` during this story's research — no repository-layer change needed).

- [x] Task 6: Fix `ReceiversScreen`'s list-row display for schedule-less receivers (AC: 4)
  - [x] `ui/receivers/ReceiversScreen.kt` lines ~215–219 — the schedule-count summary text currently reads `"${entry.scheduleTimes.size}×/day · %d–%d images"` unconditionally, which would literally show "0×/day" for a schedule-less receiver. Change to show `"Uses master schedule"` in place of the count when `entry.scheduleTimes.isEmpty()`, per `DESIGN.md#Components` (`receiver-row`: "schedule summary (e.g. '4×/day', or 'Uses master schedule' if the receiver has none of its own)"):
    ```kotlin
    val scheduleSummary = if (entry.scheduleTimes.isEmpty()) "Uses master schedule" else "${entry.scheduleTimes.size}×/day"
    Text(
        text = "$scheduleSummary · %d–%d images".format(receiver.minCount, receiver.maxCount),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    ```
  - [x] Lines ~223–230 — the "Needs N more schedule time(s)" warning currently fires whenever `entry.scheduleTimes.size < MIN_SCHEDULE_TIMES`, which incorrectly fires for `size == 0` too (a schedule-less receiver is now valid, not a migration artifact needing attention). Guard it the same way as Task 5's fix:
    ```kotlin
    if (entry.scheduleTimes.isNotEmpty() && entry.scheduleTimes.size < MIN_SCHEDULE_TIMES) {
        Text(
            text = "Needs ${MIN_SCHEDULE_TIMES - entry.scheduleTimes.size} more schedule time(s)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    ```
    Only a genuinely partial schedule (1–3 times) still shows this warning now.
  - [x] **Do not touch** the swipe-to-delete/`AlertDialog` logic, the `HorizontalDivider`, or anything above the schedule-summary `Text` in `ReceiverRow`.

- [x] Task 7: New Settings UI section for the master schedule (AC: 1, 3)
  - [x] `ui/settings/SettingsViewModel.kt`: add `private val masterScheduleRepository: MasterScheduleRepository` to the constructor. Add:
    ```kotlin
    val masterScheduleTimes: StateFlow<List<Int>?> =
        masterScheduleRepository.observeScheduleTimes()
            .map<List<Int>, List<Int>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    ```
    (mirrors the existing `retentionDays: StateFlow<Int?>` shape exactly — `null` means "not yet loaded," same convention). Add:
    ```kotlin
    fun onSaveMasterSchedule(times: List<Int>, onResult: (Boolean) -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val result = masterScheduleRepository.setScheduleTimes(times)
                onResult(result is AppResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }
    ```
    Reuses the existing single `_isSaving`/`isSaving` flow already on this ViewModel — Settings only ever has one dialog open at a time, so a single in-flight-save guard shared across both the retention picker and the new master-schedule editor is correct, not a corner cut. Update `factory(container)` to pass `container.masterScheduleRepository` as the second constructor argument.
  - [x] `ui/settings/SettingsScreen.kt`: add a new `Card` section below the existing retention `Card` (same file, same screen — `DESIGN.md#Components`: "Sits below the retention row on the same screen, its own labeled section ('Master schedule')"), labeled "Master schedule", showing the current `masterScheduleTimes` as a comma-or-list summary (e.g. "4 times/day" or list them out — match the visual weight of the existing retention row, a simple label-left/tap-to-edit pattern) and opening a dialog/inline editor on tap.
  - [x] The master-schedule editor itself must reuse `ReceiverEditScreen`'s existing "Schedule times" list component **verbatim** — same add/remove-by-index rows, same `TimePickerDialog`/`rememberTimePickerState` usage, same duplicate-time detection, same inline-error-under-the-list pattern — per `DESIGN.md#Components` ("reuses the exact 'Schedule time list' component from Receiver Edit... verbatim") and `EXPERIENCE.md#Component Patterns`. The one behavioral difference: validation here is **always** `times.size < MIN_SCHEDULE_TIMES` (no `isNotEmpty() &&` guard — zero is never valid for the master schedule itself, since there's nothing for *it* to fall back to). Concretely: extract the schedule-time-list body (the `Column` containing the "Schedule times" label, the `forEachIndexed` rows, the "Add time" button, and the duplicate/error text — `ReceiverEditScreen.kt` lines ~236–261) into a small shared composable (e.g. `ScheduleTimeListEditor` in a new or existing shared UI file) that both `ReceiverEditScreen` and the new Settings section call, parameterized by the current `SnapshotStateList<Int>`/`TimePickerDialog` trigger and the minimum-check predicate (`isNotEmpty() && size < MIN` vs. always `size < MIN`) — this avoids duplicating the same ~25 lines of Compose UI twice, while still satisfying "verbatim" reuse (identical rendered behavior, one implementation, two call sites).
  - [x] Update `SettingsScreen.kt`'s existing doc comment (currently "This is Settings' only content (Story 3.2)") — it is no longer the only content; note the new master-schedule section and its story (2.3).

- [x] Task 8: Tests (AC: 1, 2, 3, 4)
  - [x] `app/src/test/java/com/ris/imagedistributor/data/repository/MasterScheduleRepositoryImplTest.kt` (new, JVM unit test, mocked `AppDatabase`/`MasterScheduleDao` — same mocking shape as other repository unit tests in this codebase): `getScheduleTimes()` returns DAO's times when non-empty; returns `DEFAULT_MASTER_SCHEDULE_TIMES` when the DAO returns an empty list (the fresh-install case from Task 1's gotcha); `setScheduleTimes()` rejects a list of size 0 **and** a list of size 1–3, both with `AppResult.Failure(FailureReason.INVALID_INPUT)`, and does not call `deleteAll()`/`insert()` at all in either case (the master schedule's own `setScheduleTimes` requires `size >= MIN_SCHEDULE_TIMES` unconditionally — this is the one place in the whole codebase where zero is *not* valid, deliberately contrasting with `ReceiverRepositoryImpl`); `setScheduleTimes()` with a valid (≥4) list calls `deleteAll()` then `insert()` once per time, inside a transaction.
  - [x] `app/src/test/java/com/ris/imagedistributor/worker/SendDispatcherTest.kt` (extend existing file, do not restructure existing tests): add a `masterScheduleRepository` mock to `setUp()` (default stub: `coEvery { masterScheduleRepository.getScheduleTimes() } returns AppResult.Success(emptyList())` so existing tests — all of which use receivers with their own schedule times — are unaffected). Add new cases: a receiver with `scheduleTimes = emptyList()` and a due master-schedule time dispatches using the master schedule's time; a receiver with its own non-empty `scheduleTimes` never calls `masterScheduleRepository.getScheduleTimes()`'s result even when the master schedule also has a due time at that same slot (verifies "even partially" from AD-16 — construct a receiver with one schedule time, confirm only that receiver's own time is used, never merged with the master schedule's other times); `masterScheduleRepository.getScheduleTimes()` returning `AppResult.Failure` results in schedule-less receivers simply having nothing dispatched that run (no crash, no thrown exception — contrast with `getAllWithSchedules()` failure, which does throw).
  - [x] `app/src/test/java/com/ris/imagedistributor/ui/settings/SettingsViewModelTest.kt` (extend existing file): add cases for `masterScheduleTimes` StateFlow emitting the repository's values (Turbine, same pattern as `retentionDays`'s existing test) and `onSaveMasterSchedule` success/failure paths (same shape as the existing `onSave`/retention tests), including the `_isSaving` guard against a concurrent second call.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (extend existing file): add `migrate7To8_createsMasterScheduleTableWithFourSeededRows()` — run `MIGRATION_7_8` via `MigrationTestHelper` against a v7 database, then query `master_schedule` directly and assert exactly 4 rows exist with the seeded times. Extend the existing full-chain test (`migrate1Through...`) to run through `8`.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/data/repository/ReceiverEditScreenTest.kt` or equivalent Compose UI test (check for an existing `ReceiverEditScreen`-focused instrumented test first; if `ReceiversScreenTest.kt` already covers this screen, extend it rather than creating a new file) — add a case confirming Save succeeds with zero schedule times (no error shown, `onDone` fires) and a case confirming Save is still blocked with exactly 2 schedule times (error text shown, `onDone` does not fire) — covers AC4 at the UI layer, not just the unit-level validation logic.
  - [x] Full regression pass: `gradle :app:testDebugUnitTest` and `gradle :app:connectedDebugAndroidTest`, both verified via their XML results (not just "BUILD SUCCESSFUL") per this project's established verification standard — confirm the pre-existing test counts only grow, never shrink, and 0 failures/errors.

- [x] Task 9: Live on-device verification (AC: 1, 2, 3, 4)
  - [x] `adb uninstall` the existing app first (a version-8 migration and `ExistingPeriodicWorkPolicy.KEEP`-governed `SendWorker` request both benefit from a clean install for verification — this project's established gotcha), then fresh `installDebug`.
  - [x] Walk through Setup → add a receiver with zero schedule times (confirm Save succeeds, list shows "Uses master schedule") → add a second receiver with exactly 2 schedule times (confirm Save is blocked with the inline error) → open Settings, confirm the new "Master schedule" section shows 4 default times, edit it (add/remove a time, confirm the same minimum-4 error blocks an attempt to go below 4) → confirm a save of ≥4 times succeeds.
  - [x] If feasible within the emulator's clock, advance time (via `adb shell date` or waiting) past one of the master schedule's default times and confirm the zero-schedule receiver actually receives a dispatched send using that master-schedule time (check the Dashboard, per Story 3.1) — this is the actual end-to-end proof of AC2, beyond the unit-test-level `SendDispatcher` verification.

### Review Findings

- [x] [Review][Patch] `MIGRATION_7_8`'s seed-INSERT loop isn't idempotent [`app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt`:151-166] — `CREATE TABLE IF NOT EXISTS` silently no-ops on a retried migration, but the `listOf(540, 720, 900, 1080).forEach { db.execSQL("INSERT...") }` loop had no existence guard, so a migration retry (e.g. process death mid-migration on an Android upgrade — a real, if rare, scenario) would have duplicated the 4 seed rows. Fixed: the seed loop now only runs when `SELECT COUNT(*) FROM master_schedule` is 0. Verified via `connectedDebugAndroidTest` (67/67 passing, including `AppDatabaseMigrationTest`).
- [x] [Review][Patch] Missing test coverage for `ReceiversScreen`'s partially-filled-schedule warning [`app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt`] — no test rendered `ReceiversScreen` with a receiver holding 1-3 schedule times and asserted the list row still shows "Needs N more schedule time(s)" (Task 6's `entry.scheduleTimes.isNotEmpty() && entry.scheduleTimes.size < MIN_SCHEDULE_TIMES` guard). Fixed: added `showsNeedsMoreScheduleTimesWhenReceiverScheduleIsPartiallyFilled`, asserting both the "2×/day" summary and the "Needs 2 more schedule time(s)" warning for a 2-time receiver. Verified via `connectedDebugAndroidTest` (67/67 passing).
- [x] [Review][Defer] `MIN_SCHEDULE_TIMES` (value `4`) is duplicated across three files — `ReceiverEditScreen.kt`, `SettingsScreen.kt` (as `MIN_MASTER_SCHEDULE_TIMES`), and `MasterScheduleRepositoryImpl.kt` — deferred, pre-existing pattern. Each site's doc comment explains this is intentional (a data-layer class must not depend on a UI-layer constant), consistent with this codebase's established layering discipline, but it remains a manual-sync risk if the value ever changes.

## Dev Notes

- **This story's core mechanism is small and precise: one `.ifEmpty { masterScheduleTimes }` in `SendDispatcher.dispatchDueSlots()`.** Resist the temptation to build anything more elaborate (a merge, a per-slot fallback, a materialized/copied schedule) — AD-16 is explicit and narrow: all-or-nothing per receiver, queried live, never copied. If the implementation ends up more complex than Task 4 describes, stop and re-read AD-16 before continuing.
- **The master schedule's own minimum-4 rule has no "or zero" exception, unlike a receiver's schedule.** This is easy to get backwards while implementing Task 7's shared `ScheduleTimeListEditor`, since the whole point of that component is to be reused between two screens with *almost* the same rule. Get the predicate parameterization right (Task 7's last bullet) — a bug here would let the operator empty out the master schedule entirely, silently breaking every schedule-less receiver in the app.
- **Two different "at least 4, seeded at DB creation" singleton/near-singleton tables now exist with two different real seeding mechanisms** (`RetentionSetting`: repository-level default only, no meaningful "seed" since it's a single scalar; `MasterSchedule`: migration seeds 4 real rows for upgrades, repository-level default covers fresh installs) — both converge on the same actual guarantee (a caller always gets a sensible value), by design, not by accident. Don't try to unify these into one mechanism; the existing precedent (established across Stories 1.1/3.2) is exactly this pattern of migration-seeds-for-upgrades-plus-repository-defaults-for-fresh-installs, and `MasterSchedule` is simply the first case where the "seeded" data is a list rather than a scalar.
- **`ReceiverRepository`/`ReceiverRepositoryImpl` need zero code changes for this story** — confirmed by reading both files during this story's research: `addReceiver`/`updateReceiver`'s `scheduleTimes.forEach { ... }` loops already handle an empty list correctly (zero iterations, zero rows inserted). Only the UI-layer validation (Task 5) and the UI-layer display (Task 6) needed to change to actually *allow* an empty list to reach the repository in the first place.
- **`SendWorker.kt` itself needs no changes** — it's a thin `CoroutineWorker` adapter that delegates to `SendDispatcher.dispatchDueSends()` (Story 2.2); all of this story's logic lives inside `SendDispatcher`, which `SendWorker` already calls unconditionally every run.
- **Reused UI-component extraction (Task 7) is the one piece of this story that touches already-shipped, working code (`ReceiverEditScreen.kt`) for a reason beyond this story's own validation relaxation.** Do this extraction carefully — `ReceiverEditScreen`'s existing instrumented tests (if any target the schedule-time-list rows specifically) must continue to pass unchanged after the extraction; this is a refactor, not a rewrite, and DESIGN.md's "verbatim" reuse requirement means the rendered output must be pixel/behavior-identical to today's `ReceiverEditScreen`, just also callable from Settings.
- **`MIN_SCHEDULE_TIMES` stays `internal const val` in `ReceiverEditScreen.kt` (its current home)** — both `ReceiversScreen.kt` (Task 6) and the new Settings section (Task 7) already import it from there today (`ReceiversScreen.kt` does), so there's no need to relocate it to a shared constants file for this story.

### Previous Story Intelligence (from Story 3.2)

- **`RetentionSettingDao`'s `@Update`-silently-no-oped-on-fresh-installs bug (fixed via `@Upsert` in this session's own Story 1.2 code-review round, and documented directly in `RetentionSetting.kt`'s doc comment) is the single most relevant precedent for this story's Task 1 gotcha.** The exact same class of bug (assuming a migration-seeded row/rows always exist, when a fresh install never runs any migration) is trivially reproducible here if `MasterScheduleRepositoryImpl` is written to assume `getAllOnce()`/`observeAll()` always return ≥4 rows. Task 2's `DEFAULT_MASTER_SCHEDULE_TIMES` fallback is this story's version of that same fix, applied proactively instead of needing a second code-review round to catch it.
- **`RetentionPolicy.observeCutoff()`'s refactor (Story 3.2's own dev-story work) is a useful shape reference for "share one computed value between a `Flow`-based observer and a one-shot suspend read without duplicating logic"** — `MasterScheduleRepositoryImpl` has a milder version of the same shape (`observeScheduleTimes()`/`getScheduleTimes()` both need the same `ifEmpty`/`catch` fallback default), though it's simple enough here that a shared private helper is optional, not required.
- **`Turbine` + `StandardTestDispatcher` + explicit first-`awaitItem()` assertions remain the established `StateFlow`-testing convention** — apply this exactly for `SettingsViewModelTest`'s new `masterScheduleTimes` case, same as the existing `retentionDays` test in that file.
- **Emulator app-data reset / `ExistingPeriodicWorkPolicy.KEEP` gotchas** have recurred every story this session — `adb uninstall` before reinstalling remains mandatory for verifying this story's `SendDispatcher` change actually takes effect on-device, not just in unit tests.

## Dev Agent Record

### Context Reference

<!-- Path(s) to story context XML/JSON will be added here by context workflow -->

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL, verified via XML: 144 tests, 0 failures, 0 errors, 0 skipped (was 102 before this story).
- `gradle :app:connectedDebugAndroidTest` (against `imagedrop_test(AVD) - 15`) — first run caught a real bug (see below); second run BUILD SUCCESSFUL, verified via XML: 56 tests, 0 failures, 0 errors.
- Live on-device verification: `adb uninstall` (app wasn't previously installed, so this was a no-op — equivalent to a clean install anyway) → `gradle :app:installDebug` → fresh Setup → confirmed every AC live (see Completion Notes).
- No Gradle wrapper or Android Studio was present in the initial shell environment; the user pointed to `C:\Android\gradle\gradle-9.4.1` and `C:\Android\sdk` (from `local.properties`'s `sdk.dir`), plus `C:\Program Files\Android\openjdk\jdk-21.0.8` for `JAVA_HOME` — all commands in this story used that toolchain directly (no wrapper script exists in this repo).

### Completion Notes List

- All 9 tasks implemented and covered by 32 new/extended tests: 15 new/extended JVM unit tests (`SendDispatcherTest` +3, `SettingsViewModelTest` +5) plus a new `MasterScheduleRepositoryImplTest` (8 instrumented cases, not JVM — see deviation below), plus `AppDatabaseMigrationTest` +2, `ReceiversScreenTest` +4 (1 fixed for the new behavior, 3 new), `SettingsScreenTest` +4 new master-schedule cases and its shared `containerWith` helper extended. 144 unit tests total (was 102), 56 instrumented tests total (was 51), all passing, verified via XML.
- **One deliberate, reasoned deviation from the story's own Task 8 text**: `MasterScheduleRepositoryImplTest` ended up as an **instrumented** test (real in-memory Room DB via `Room.inMemoryDatabaseBuilder`), not the "JVM unit test, mocked `AppDatabase`/`MasterScheduleDao`" the story specified. Reason: `setScheduleTimes()` calls `AppDatabase.withTransaction { ... }`, which is a `suspend inline` extension function — inline functions are copied into the caller's bytecode at compile time, so there is no function symbol left at runtime for `mockkStatic` to intercept, making the transactional path fundamentally unmockable. This is the exact same constraint `ReceiverRepositoryImplTest.kt` already documents in its own header comment (from Story 1.3), and this test now follows that same established precedent verbatim.
- **A real bug was caught by the test suite itself, not just written to pass**: `MasterScheduleRepositoryImpl.observeScheduleTimes()`'s original implementation only had a `.catch { }` fallback for a DB *read failure*, not for a genuinely *empty* table (the actual fresh-install case) — `dao.observeAll()` on zero rows emits `emptyList()` normally, no exception, so `.catch` never fired. The first `connectedDebugAndroidTest` run failed exactly one test (`observeScheduleTimes_fallsBackToDefaultWhenEmpty`, expected `[540, 720, 900, 1080]`, got `[]`), which correctly caught this. Fixed by adding `.ifEmpty { DEFAULT_MASTER_SCHEDULE_TIMES }` inside the `.map`, mirroring `getScheduleTimes()`'s already-correct handling of the same case. Full unit + instrumented suites re-ran green after the fix.
- **`SendWorkerTest.kt` (Story 2.2, untouched by this story's own task list) needed one addition**: its manual `SendDispatcher(...)` construction was missing the new `masterScheduleRepository` constructor parameter, caught immediately by `compileDebugAndroidTestKotlin` (a compile error, not a silent gap). Added a plain `mockk<MasterScheduleRepository>()` — this test never exercises the dispatch loop itself (it's a framework-wiring smoke test with an empty receiver list), so no stubbing was needed.
- **Live on-device verification, explicit, per AC:**
  - **AC1 & AC3** (Settings master schedule): confirmed the fresh-install default (4 seeded times: 09:00/12:00/15:00/18:00) displays correctly ("Master schedule · 4 times/day"); opened the editor, removed one time down to 3, tapped Save — correctly blocked with "Add at least 4 schedule times." (proving the master schedule's own minimum has no "or zero" exception); Cancel correctly discarded the in-progress edit, row still showed "4 times/day" afterward.
  - **AC4** (partial-fill still blocked, empty now valid): added a receiver ("Asha") with zero schedule times — Save succeeded, list row correctly read "Uses master schedule · 2–5 images" (not "0×/day"). Added a second receiver ("Kiran") with exactly 2 schedule times (07:00, 18:00) — Save was correctly blocked with "Add at least 4 schedule times.", and confirmed via the list afterward that Kiran was never persisted (only Asha appears).
  - **AC2** (actual dispatch fallback): verified exhaustively at the unit level (3 dedicated `SendDispatcherTest` cases: a schedule-less receiver dispatches using a due master-schedule time; a receiver with its own schedule never consults the master schedule even partially; a master-schedule read failure leaves schedule-less receivers with nothing dispatched, no crash). **Not** verified via an actual end-to-end live delivery on this pass — the Image Library was empty on this fresh install and the system photo picker proved unreliable to drive via `adb shell input` in this session (unrelated to this story's own code — a Story 1.2 concern), so there were no images for a real dispatch to actually select and send. `SendWorker` was confirmed running successfully on-device via logcat (`WM-WorkerWrapper: Starting work for com.ris.imagedistributor.worker.SendWorker`) with zero crashes throughout the entire verification session (confirmed via a final `logcat | grep FATAL EXCEPTION` sweep, empty result).
  - `adb uninstall` before `installDebug`: the app was not previously installed on the test emulator (confirmed via `adb shell pm list packages`), so this step was effectively a clean install already — no `ExistingPeriodicWorkPolicy.KEEP` staleness to worry about for this pass.
- **`ReceiversScreenTest.kt`'s pre-existing `addReceiverShowsInlineErrorWhenFewerThanFourScheduleTimes` test would have failed after this story's changes** (it exercised a brand-new, zero-schedule form and asserted the error *did* show — exactly the case this story makes valid). Renamed/rewrote it to `addReceiverDoesNotShowScheduleErrorWhenScheduleIsEmpty` asserting the opposite, and added two new dedicated AC4 tests (`editReceiverSucceedsWithZeroScheduleTimes`, `editReceiverBlocksSaveWithPartiallyFilledSchedule`) using the `existing` parameter to seed schedule state directly rather than driving the real `TimePickerDialog` widget through Compose UI test APIs (which has no straightforward semantics-based selector for a Material3 dial).
- **`ReceiverEditScreen.kt`'s "Schedule times" list body was extracted into a new shared `ui/components/ScheduleTimeListEditor.kt` composable** (plus a shared `addScheduleTime` helper function), per Task 7's requirement to reuse it verbatim in the new Settings master-schedule section. `ReceiverEditScreen.kt` now calls this shared composable instead of inlining the same ~25 lines; its own validation predicate (`isNotEmpty() && size < MIN`) stays local to each call site, only the display/interaction body is shared — the master schedule's own predicate (`size < MIN`, unconditionally) is likewise local to `SettingsScreen.kt`'s `MasterSchedulePickerDialog`.

### File List

**New:**
- `app/src/main/java/com/ris/imagedistributor/data/local/MasterSchedule.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/MasterScheduleDao.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/MasterScheduleRepository.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/MasterScheduleRepositoryImpl.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/components/ScheduleTimeListEditor.kt`
- `app/src/androidTest/java/com/ris/imagedistributor/data/repository/MasterScheduleRepositoryImplTest.kt`

**Updated:**
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (added `MasterSchedule::class`, `masterScheduleDao()`, version 7→8, `MIGRATION_7_8`; `MIGRATION_1_2`–`MIGRATION_6_7` untouched)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (registered `MIGRATION_7_8`; added `masterScheduleRepository`; passed it into `sendDispatcher`)
- `app/src/main/java/com/ris/imagedistributor/worker/SendDispatcher.kt` (added `masterScheduleRepository` constructor param; fetches master schedule once per run in `dispatchDueSends()`; `dispatchDueSlots()` now uses `receiverWithSchedules.scheduleTimes.ifEmpty { masterScheduleTimes }`)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiverEditScreen.kt` (schedule validation relaxed to allow empty; class doc comment citation updated; schedule-time-list body extracted to `ScheduleTimeListEditor`; `TextButton` import removed as now-unused)
- `app/src/main/java/com/ris/imagedistributor/ui/receivers/ReceiversScreen.kt` (schedule-count summary shows "Uses master schedule" when empty; "Needs N more" warning guarded against firing on an empty list)
- `app/src/main/java/com/ris/imagedistributor/ui/settings/SettingsViewModel.kt` (added `masterScheduleRepository` constructor param, `masterScheduleTimes` StateFlow, `onSaveMasterSchedule`; `factory()` updated)
- `app/src/main/java/com/ris/imagedistributor/ui/settings/SettingsScreen.kt` (added the "Master schedule" section/row + `MasterSchedulePickerDialog`, reusing `ScheduleTimeListEditor`; renamed `showDialog` to `showRetentionDialog`; doc comment updated)
- `app/src/test/java/com/ris/imagedistributor/worker/SendDispatcherTest.kt` (added `masterScheduleRepository` mock + default stub; 3 new fallback-specific test cases)
- `app/src/test/java/com/ris/imagedistributor/ui/settings/SettingsViewModelTest.kt` (added `masterScheduleRepository` mock; 5 new test cases)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (added `migrate7To8_createsMasterScheduleTableWithFourSeededRows`; renamed/extended the full-chain test to `migrate1Through8...`)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ReceiversScreenTest.kt` (fixed one pre-existing test for the new empty-schedule behavior; added 3 new tests)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/SettingsScreenTest.kt` (`containerWith` helper extended to also stub `MasterScheduleRepository`, now returns a `Triple`; added 4 new master-schedule tests)
- `app/src/androidTest/java/com/ris/imagedistributor/worker/SendWorkerTest.kt` (added a `masterScheduleRepository` mock to the manual `SendDispatcher(...)` construction — compile-error fix, not a behavioral change)

## Change Log

- 2026-07-12: Story implemented end-to-end (Tasks 1–9). Added the `MasterSchedule` entity/DAO/repository (migration 7→8 seeds 4 defaults for upgrades; repository-level `ifEmpty`/`.catch` fallback covers fresh installs — the same dual-mechanism pattern established by `RetentionSetting`), wired it into `AppContainer` and `SendDispatcher`'s dispatch loop (`.ifEmpty { masterScheduleTimes }`, fetched once per run), relaxed `ReceiverEditScreen`'s validation to allow an empty schedule while still blocking a partial one, fixed `ReceiversScreen`'s list-row display ("Uses master schedule" / guarded warning), and added a new Settings UI section reusing a newly-extracted shared `ScheduleTimeListEditor` composable. 32 new/extended tests, all passing (144 unit + 56 instrumented, both verified via XML). One real bug found and fixed by the test suite itself (`observeScheduleTimes()`'s missing empty-table fallback). Live-emulator verification confirmed AC1, AC3, and AC4 end-to-end with screenshots (fresh-install master-schedule defaults, min-4 enforcement with no zero-exception, a zero-schedule receiver saving successfully and displaying "Uses master schedule", a partially-filled receiver correctly blocked); AC2's dispatch logic is exhaustively unit-tested but was not exercised via an actual live delivery on this pass (empty Image Library + a flaky system photo picker in this session, unrelated to this story's own code). Zero crashes throughout the entire live-verification session, confirmed via logcat. Status moved to `review`.
