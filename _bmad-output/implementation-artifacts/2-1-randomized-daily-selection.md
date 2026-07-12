---
baseline_commit: NO_VCS
---

# Story 2.1: Randomized Daily Selection

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want each receiver to automatically get a randomized, non-repeating set of images at their scheduled time,
so that my daily manual sending job is replaced without receivers noticing obvious repeats.

## Acceptance Criteria

1. Given a receiver's scheduled send time has arrived, When the app selects images for that receiver, Then it picks a random count between that receiver's configured min and max, And it selects that many currently-active images, excluding any image sent to that receiver in the last 7 days. [Source: epics.md#Story 2.1] [Source: mechanics.md#Selection algorithm steps 1-3]
2. Given the 7-day-exclusion pool is smaller than the needed count, When the app can't reach the needed count without repeating, Then it falls back to allowing repeats until the count is reached. [Source: epics.md#Story 2.1] [Source: mechanics.md#Selection algorithm step 4]
3. Given the total active image count is itself smaller than the needed count, When the app selects images, Then it sends only the available active images — no duplicates, no topping up. [Source: epics.md#Story 2.1] [Source: mechanics.md#Selection algorithm step 5]

**Scope boundary (from epics.md's own "Technical note"):** this story is the *selection algorithm only* — `ImageSelectionEngine`, called with a receiver's id/min/max, returning the images it would send. It is **not** wired to a Worker and does **not** actually queue or deliver anything — that is Story 2.2 (`SendWorker`, offline-safe queued delivery). This story does introduce the `Transmission` entity/DAO (needed to query the 7-day exclusion window), but only the read path the engine needs; **do not build the write path** (inserting `PENDING` rows, updating `status`/`attemptCount`) — that belongs to Story 2.2, which explicitly "extends" this DAO. Building it here would be forward-scoping into work the next story owns, and there is no way to test it meaningfully yet (nothing writes to `receivers`' schedule times a live send).

## Tasks / Subtasks

- [x] Task 1: `Transmission` entity, DAO, and schema migration (AC: 1, 2) — **extends the existing `AppDatabase` (now at version 4 after Story 1.3's Task 7), does not create a second one**
  - [x] `data/local/Transmission.kt`: `id: Long` (autoincrement), `receiverId: Long`, `imageId: Long`, `status: String` (stored as the enum name — `PENDING`\|`SENT`\|`FAILED`, per the project's established enum-as-String convention, matching `Receiver.channel`), `attemptCount: Int`, `sentAt: Long?` (nullable epoch-millis — only set once `status` becomes `SENT`, per AD-13; `null` while `PENDING`/`FAILED`). [Source: ARCHITECTURE-SPINE.md#AD-6, #AD-7, #AD-13, #Consistency Conventions]
  - [x] Define a `TransmissionStatus` enum (`PENDING`, `SENT`, `FAILED`) mirroring `ReceiverChannel`'s pattern in `data/local/Receiver.kt` — a plain enum next to the entity, `.name`/`valueOf()` at the boundary, no Room `TypeConverter`. This story only ever reads `SENT` rows (see Task 2), but the full enum should exist now since the schema/column is being defined now.
  - [x] `data/local/TransmissionDao.kt`: **only the read query this story's engine needs** — `@Query("SELECT imageId FROM transmissions WHERE receiverId = :receiverId AND status = 'SENT' AND sentAt >= :sinceEpochMillis") suspend fun getSentImageIdsSince(receiverId: Long, sinceEpochMillis: Long): List<Long>`. **Do not add `insert`/`update` methods in this story** — Story 2.2 adds those when it builds the actual queue-write path (see Scope boundary above).
  - [x] Update `data/local/AppDatabase.kt`: add `Transmission::class` to `entities`, bump `version` to `5`, add `abstract fun transmissionDao(): TransmissionDao`, add a `MIGRATION_4_5` (creates the `transmissions` table — a plain `CREATE TABLE`, no data to preserve since this entity is wholly new) alongside the existing `MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4` — **do not touch those three**, all four must stay registered. Real data (locked `ComplianceState`, `Image` rows, `Receiver`+`ReceiverSchedule` rows) already exists on the test device — no destructive fallback. [Source: ARCHITECTURE-SPINE.md#AD-15]
  - [x] Register `MIGRATION_4_5` in `AppContainer`'s `.addMigrations(...)` call alongside the other three.
  - [x] Add an index on `(receiverId, sentAt)` (or at minimum `receiverId`) for the exclusion query — this table will grow to 30 days of history per AD-8/CAP-8's retention window, an unindexed scan gets worse every day until Story 3.2's purge exists.
- [x] Task 2: `TransmissionRepository` (AC: 1, 2)
  - [x] `data/repository/TransmissionRepository.kt` (interface) + `TransmissionRepositoryImpl.kt` — wraps `TransmissionDao`. Method: `suspend fun getRecentlySentImageIds(receiverId: Long, since: Instant): AppResult<List<Long>>` — converts `since` to epoch-millis at the boundary (AD-7: `Instant` in domain, epoch-millis in storage), calls the DAO, wraps in `AppResult`/`runCatchingDb` exactly like `ReceiverRepositoryImpl`'s pattern (`CancellationException` rethrown before the generic catch). [Source: ARCHITECTURE-SPINE.md#AD-7, #AD-8] [Source: data/repository/ReceiverRepositoryImpl.kt — mirror this pattern exactly]
  - [x] No domain service inside this repository — it's a thin AppResult-wrapping read, same as every other repository in this codebase. The actual selection *logic* (random count, exclusion-then-fallback) lives in `ImageSelectionEngine` (Task 4), per AD-10.
- [x] Task 3: One-shot active-images read on `ImageRepository` (AC: 1, 2, 3)
  - [x] `data/local/ImageDao.kt`: add `@Query("SELECT * FROM images WHERE active = 1") suspend fun getActive(): List<Image>` — a one-shot suspend query, distinct from the existing `observeAll(): Flow<List<Image>>` used by the UI. `ImageSelectionEngine` needs a single snapshot at selection time, not a live-updating Flow it would have to collect-and-cancel awkwardly inside a suspend function.
  - [x] `data/repository/ImageRepository.kt`/`ImageRepositoryImpl.kt`: add `suspend fun getActiveImages(): AppResult<List<Image>>`, wrapping the new DAO query in the same `runCatchingDb` pattern already used by `uploadImages`/`setActive`. **Do not touch `observeImages()`, `uploadImages()`, or `setActive()`** — this is a pure addition.
- [x] Task 4: `ImageSelectionEngine` (CAP-3 algorithm) (AC: 1, 2, 3)
  - [x] `domain/ImageSelectionEngine.kt` — constructor takes `imageRepository: ImageRepository`, `transmissionRepository: TransmissionRepository`, and `random: kotlin.random.Random = kotlin.random.Random.Default` (the last one **must** be injectable/overridable — this is what makes the "random count" and "random picks" behavior deterministically testable; do not hardcode `Random.Default` calls inline where a test can't substitute them). Mirrors `ComplianceGate`'s constructor-injected-repository shape. [Source: domain/ComplianceGate.kt — mirror this pattern exactly] [Source: ARCHITECTURE-SPINE.md#AD-10]
  - [x] One suspend function, e.g. `suspend fun selectImagesFor(receiverId: Long, minCount: Int, maxCount: Int): AppResult<List<Image>>`, implementing `mechanics.md`'s 5-step algorithm **exactly**, in order:
    1. Pick a random count `Z` in `[minCount, maxCount]` inclusive, using the injected `random` (re-rolled fresh on every call — no caching/memoizing Z across calls for the same receiver).
    2. Fetch all currently-active images (`imageRepository.getActiveImages()`) and the set of image ids sent to this receiver in the last 7 days (`transmissionRepository.getRecentlySentImageIds(receiverId, Instant.now().minus(7, ChronoUnit.DAYS))`). Build the eligible pool = active images whose id is **not** in that recently-sent set.
    3. If the eligible pool has at least `Z` images: pick `Z` at random from it, no duplicates within the returned batch.
    4. Else (eligible pool smaller than `Z` — the 7-day exclusion made the math impossible): fall back to picking from **all active images** (ignoring the 7-day rule), still no duplicates within the batch, until `Z` is reached or all active images are exhausted.
    5. If the total active image count itself is smaller than `Z` (independent of the repeat-fallback): return only however many active images exist. **Never duplicate or "pad" the result to reach `Z`.**
  - [x] Any DB-layer failure from either repository call should surface as `AppResult.Failure` from `selectImagesFor` — don't let a `Failure` from one repository call silently get treated as "empty list, proceed anyway" (that would silently violate the exclusion rule by pretending nothing was ever sent). Propagate the failure.
  - [x] `CancellationException` must propagate uncaught, per this codebase's established convention everywhere else — don't swallow it while combining the two repository calls.
- [x] Task 5: AppContainer wiring (AC: 1, 2, 3)
  - [x] Add `transmissionDao`, `MIGRATION_4_5` registration, `transmissionRepository: TransmissionRepository by lazy { ... }`, and `imageSelectionEngine: ImageSelectionEngine by lazy { ... }` to `di/AppContainer.kt`, following the exact `by lazy` pattern already used for every other dependency. `imageSelectionEngine` takes the real `Random.Default` (only tests inject a different one).
- [x] Task 6: Tests (AC: 1, 2, 3)
  - [x] `ImageSelectionEngineTest` (JVM unit test, mockk for both repositories, an injected deterministic `Random` — e.g. a fixed-seed `Random(42)` or a hand-rolled fake that returns a scripted sequence) covering, at minimum:
    - Z is chosen within `[min, max]` and re-rolled independently across calls (assert two calls with a `Random` that returns different values on successive draws produce different-sized results).
    - Normal path: eligible pool (active minus 7-day-excluded) has ≥ Z images → exactly Z returned, all excluded-in-last-7-days images absent from the result.
    - Fallback path: eligible pool < Z → result falls back to allowing repeats from all active images, still reaches Z (assuming enough total active images exist), no duplicate `Image` entries **within a single returned batch** (per step 3/4's "no duplicates within the batch" wording — re-sends of a previously-sent image across different calls/days are expected and fine, that's the whole point of the 7-day window being a window, not permanent exclusion).
    - Starvation path: total active count < Z → returns only the available active images, no padding/duplication to reach Z.
    - A repository `Failure` from either call propagates as `AppResult.Failure`, not a silently-empty success.
    - `CancellationException` from either repository call propagates, not swallowed.
  - [x] `TransmissionRepositoryImplTest` — mirror `ImageRepositoryImplTest`'s JVM unit-test-with-mocked-DAO pattern (this repository, unlike `ReceiverRepositoryImpl`, has no cross-table transaction, so it doesn't need the real-in-memory-Room-via-androidTest treatment `ReceiverRepositoryImplTest` needed — a mocked `TransmissionDao` is sufficient and simpler).
  - [x] Extend `AppDatabaseMigrationTest` with a `migrate4To5_createsTransmissionsTable` case (or similar) verifying `MIGRATION_4_5` runs clean via `MigrationTestHelper` — this migration has no data-preservation complexity (brand-new table), so it's a simpler test than `MIGRATION_3_4`'s, but should still exist rather than being silently skipped, now that this project has established real `MigrationTestHelper` coverage as the norm going forward (Story 1.3's Task 7 precedent).
  - [x] Add a small `getActiveImages()` case to whatever test file covers `ImageRepositoryImpl` (extend `ImageRepositoryImplTest`, don't create a parallel file).

### Review Findings

- [ ] [Review][Patch] `ImageSelectionEngine.selectImagesFor` has no input validation on `minCount`/`maxCount` — `minCount > maxCount`, a negative count, or `maxCount == Int.MAX_VALUE` (integer overflow on `maxCount + 1`) all throw an uncaught `IllegalArgumentException` instead of returning `AppResult.Failure` like every other failure path in this class [domain/ImageSelectionEngine.kt]
- [ ] [Review][Patch] `TransmissionDao.getSentImageIdsSince` hardcodes the literal `'SENT'` in its SQL, disconnected from the `TransmissionStatus` enum — a future rename of the enum constant (e.g. during Story 2.2) wouldn't be caught by the compiler, silently breaking the exclusion filter [data/local/TransmissionDao.kt]
- [ ] [Review][Patch] The `(receiverId, sentAt)` index omits `status`, which the exclusion query also filters on — SQLite still filters status per-row within the indexed range [data/local/Transmission.kt]
- [ ] [Review][Patch] Confusing/contradictory in-code comments about "repeats" — one comment says "repeats allowed," the next says "never duplicating," which reads as contradictory even though both are correct (a resent-after-7-days image is not a "duplicate within one batch") [domain/ImageSelectionEngine.kt]
- [ ] [Review][Patch] No test explicitly asserts `Z` stays within `[minCount, maxCount]` across many draws — the existing tests use `min == max` (trivially bounded) or check "variance," never bounds compliance directly [domain/ImageSelectionEngineTest.kt]
- [ ] [Review][Patch] The 7-day exclusion boundary (`Instant.now().minus(7, ChronoUnit.DAYS)`) is never actually verified — every test mocks `getRecentlySentImageIds(any(), any())`, so an off-by-one in the boundary math would not be caught [domain/ImageSelectionEngineTest.kt]
- [ ] [Review][Patch] Every test in this diff is mock-only — nothing exercises `TransmissionRepositoryImpl` against a real Room DB, so the actual SQL query (including the hardcoded `'SENT'` literal above) is unverified end-to-end [data/repository/TransmissionRepositoryImplTest.kt]
- [ ] [Review][Patch] No explicit test documents the "zero active images" case — behavior is already correct per mechanics.md step 5 (returns `Success(emptyList())`, not an error), but it's worth an explicit test rather than only being implied by the starvation-path test [domain/ImageSelectionEngineTest.kt]
- [x] [Review][Defer] `Transmission.sentAt` is nullable with no DB `CHECK` constraint tying `status = 'SENT'` to a non-null `sentAt` — deferred, this depends entirely on Story 2.2's not-yet-built write path; a constraint (or defensive filter) makes more sense once that code exists and its actual failure modes are known [data/local/Transmission.kt]
- [x] [Review][Defer] `getActiveImages()` and `getRecentlySentImageIds()` run as two independent reads with no shared transaction/consistent snapshot — deferred, disproportionate for this single-device, single-operator app with no concurrent external writers; matches this project's established pattern of not over-engineering for concurrency that doesn't actually exist here [domain/ImageSelectionEngine.kt]
- [x] [Review][Defer] No single test carries seeded data through the full `1→5` migration chain (only `3→4` tests data preservation in isolation, `4→5` only tests schema shape) — deferred, the two existing dedicated tests already cover the distinct real risks separately; a combined chain-with-data test is nice-to-have, not clearly higher-value than what exists [app/src/androidTest/.../AppDatabaseMigrationTest.kt]
- [x] [Review][Defer] The seeded `Random(42)` re-roll test could in principle produce different output if Kotlin's stdlib PRNG algorithm ever changed — deferred, the test only asserts weak set-cardinality ("not all 5 draws identical"), not an exact sequence, which is extremely unlikely to break even under an algorithm change; already the more robust of the two options considered during implementation [domain/ImageSelectionEngineTest.kt]
- [x] [Review][Defer] `ImageRepositoryImpl.uploadImages` batches multiple file copies/inserts with no transaction wrapping the whole loop — deferred, this is pre-existing code from Story 1.2, untouched by this story's diff (only `getActiveImages()` was added to this file); out of scope for Story 2.1 [data/repository/ImageRepositoryImpl.kt]
- [x] [Review][Defer] `imageSelectionEngine` is wired into `AppContainer` but nothing calls it yet — deferred, explicitly by design per this story's own Dev Notes ("it will be called by SendWorker starting in Story 2.2"); normal incremental delivery, not a gap
- [x] [Review][Dismiss] Blind Hunter flagged that several "Updated" files (`AppDatabase.kt`, `ImageDao.kt`, `ImageRepository.kt`/`Impl`, `AppContainer.kt`) appear as brand-new in the diff, and that this made the diff unreliable to review as "just Story 2.1" — this is the same known `NO_VCS` diff-construction artifact already disclosed to the user before this review round (and the previous one); not a code defect.
- [x] [Review][Dismiss] Blind Hunter flagged the exported Room schema JSON files as missing from the diff, undermining migration-test validity — the schema files exist on disk (`app/schemas/.../5.json`, confirmed present), simply not included in the `.kt`-only diff patch built for reviewers; the migration tests already executed successfully against them (verified via XML in this story's own Debug Log).
- [x] [Review][Dismiss] Acceptance Auditor noted the code fetches `active`/`excludedIds` before computing `Z`, while Task 4's prose lists "pick Z" as step 1 — the auditor's own finding explicitly states this is "not a real defect" (no behavioral dependency between the two), just a statement-order observation; nothing to fix.

## Dev Notes

- **Paradigm reminder:** `domain/ImageSelectionEngine` → `data/repository/{ImageRepository,TransmissionRepository}` → `data/local` (`ImageDao`, `TransmissionDao`). This is the **first domain service** built in this codebase beyond `ComplianceGate` — read `ComplianceGate.kt` before writing `ImageSelectionEngine.kt`, its constructor-injected-repository shape and its "one suspend function, one clear job" scope are the template to copy. [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-10]
- **This is a pure algorithm story — no UI, no Worker.** `ImageSelectionEngine` is called directly by unit tests in this story; it will be called by `SendWorker` starting in Story 2.2. Do not build `SendWorker`, do not wire WorkManager, do not add any `ui/` changes. The story's own "Technical note" in epics.md explicitly frames this as normal build-on-previous-story sequencing, not a forward dependency — resist the temptation to "get ahead" on Story 2.2's scope while touching this code.
- **Reuse, don't reinvent:** `ReceiverRepositoryImpl`/`ImageRepositoryImpl` establish the `AppResult`/`runCatchingDb` repository shape (`CancellationException` rethrown before the generic catch) — `TransmissionRepositoryImpl` copies it exactly. `ComplianceGate` establishes the constructor-injected-repository domain-service shape — `ImageSelectionEngine` copies it exactly, including being a plain class (no `ViewModel`, no `Composable`, nothing UI-shaped touches this file).
- **Randomness must be injectable.** Every previous story's "randomness" (none, actually — this is the first one) sets the precedent here: don't call `kotlin.random.Random.Default` or `(min..max).random()` inline anywhere inside the algorithm's logic. Take a `Random` in the constructor, default it to `Random.Default` for production wiring, and use *that* instance for every random draw (the count `Z` and the random sub-selection from the eligible pool). This is the only way Task 6's tests can assert deterministic outcomes without flaky, statistics-based assertions.
- **"No duplicates" is scoped to a single batch, not across time.** Re-reading mechanics.md carefully: the 7-day exclusion is a *rolling window*, not a permanent "never resend" rule — an image legitimately gets sent again to the same receiver once 7 days have passed since its last send to them. Don't over-engineer a permanent-dedup mechanism; the exclusion is time-windowed by design, and Task 6's fallback-path test should reflect that repeats-across-time are expected, only repeats-*within-one-selected-batch* are forbidden.
- **`Transmission`'s write path is explicitly out of scope**, per the Scope boundary section above and epics.md's own technical note. If Task 6's tests need a `Transmission` row to exist (e.g. to prove the exclusion query works), seed it directly via `MigrationTestHelper`'s raw SQL execution in the migration test, or via a raw `SupportSQLiteDatabase.execSQL` insert in an instrumented DAO test if one is added — not via a DAO `insert()` method this story doesn't otherwise need. If you find yourself wanting a real `insert()` for a cleaner test, that's a signal Story 2.2 should provide it — flag the tradeoff in Completion Notes rather than silently building ahead of scope.
- **Testing:** JVM unit tests for `ImageSelectionEngineTest` and `TransmissionRepositoryImplTest` must actually run and pass (verified via `test-results/*.xml`, not just "BUILD SUCCESSFUL" — this project's established standard since Story 1.1). The `AppDatabaseMigrationTest` extension is instrumented (real `MigrationTestHelper`, emulator-executed) — this project now has a working, verified instrumented-test pipeline (Story 1.3's Task 7 fixed a packaging conflict that had silently blocked it since Story 1.1); use it, don't silently skip instrumented coverage citing "sandbox limitations" that no longer apply.

### Project Structure Notes

- Extends `com.ris.imagedistributor` structure — new files land in the existing `data/local/`, `data/repository/`, and `domain/` packages, no new top-level packages needed.
- Full file list this story creates:
  - `data/local/Transmission.kt` (entity + `TransmissionStatus` enum)
  - `data/local/TransmissionDao.kt`
  - `data/repository/TransmissionRepository.kt`, `data/repository/TransmissionRepositoryImpl.kt`
  - `domain/ImageSelectionEngine.kt`
  - `app/src/test/java/.../domain/ImageSelectionEngineTest.kt`
  - `app/src/test/java/.../data/repository/TransmissionRepositoryImplTest.kt`
- Files this story **updates**:
  - `data/local/AppDatabase.kt` (add `Transmission` entity, version 4→5, `MIGRATION_4_5` — `MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4` stay untouched)
  - `data/local/ImageDao.kt` (add one-shot `getActive()`)
  - `data/repository/ImageRepository.kt`/`ImageRepositoryImpl.kt` (add `getActiveImages()`)
  - `di/AppContainer.kt` (add `transmissionRepository`, `imageSelectionEngine`, register the new migration)
  - `app/src/androidTest/java/.../data/local/AppDatabaseMigrationTest.kt` (add the `4→5` migration test case)
  - `app/src/test/java/.../data/repository/ImageRepositoryImplTest.kt` (add a `getActiveImages()` test case)

### References

- [Source: SPEC.md#CAP-3]
- [Source: mechanics.md#Selection algorithm (CAP-3)]
- [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-6, #AD-7, #AD-8, #AD-10, #AD-12, #AD-13, #AD-15, #Consistency Conventions]
- [Source: epics.md#Epic 2, #Story 2.1]
- [Source: 1-3-receiver-configuration.md — established repository/migration/testing patterns to mirror, including the now-working instrumented-test pipeline and `MigrationTestHelper` precedent]
- [Source: domain/ComplianceGate.kt — the only existing domain-service to mirror the shape of]

## Previous Story Intelligence (Story 1.3, including its Task 7 rework)

- **`AppDatabase` is now version 4** with `ComplianceState`, `Image`, `Receiver`, `ReceiverSchedule` entities; this story bumps it to 5. `MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4` already exist and must not be touched or renumbered.
- **Multi-schedule rework (Task 7) does not change this story's scope.** A receiver now has one-or-more (minimum 4) `ReceiverSchedule` rows instead of one `scheduleTime`. This story's algorithm is invoked *per scheduled send* regardless of how many times a day that happens — `mechanics.md`'s "for each receiver, at each scheduled send" wording was already generic enough to cover this before Task 7 existed, confirmed during the Sprint Change Proposal analysis that triggered Task 7. `ImageSelectionEngine.selectImagesFor(receiverId, minCount, maxCount)` takes the receiver's min/max directly as parameters — it has no awareness of *which* schedule slot triggered it or how many total slots exist, and it shouldn't; that dispatch logic belongs to `SendWorker` in Story 2.2.
- **Migration testing has real precedent now.** Story 1.3's Task 7 added `AppDatabaseMigrationTest` using `MigrationTestHelper` (`androidx.room:room-testing`, wired into the `androidTest` source set with `androidTestImplementation(libs.room.testing)` and `app/schemas` registered as an androidTest asset dir in `app/build.gradle.kts`) — both are already in place, no build-file changes needed to use them again for `MIGRATION_4_5`.
- **`ReceiverRepositoryImplTest` lives in `androidTest`, not `test`** — but that was specifically because `ReceiverRepositoryImpl` uses `AppDatabase.withTransaction` across two DAOs plus a Room `@Relation` query, none of which mock meaningfully. `TransmissionRepositoryImpl` (this story) is a simple single-DAO wrapper like `ImageRepositoryImpl`/`ComplianceRepositoryImpl` — it belongs in the regular JVM `test` source set with a mocked DAO, not `androidTest`. Don't over-apply the wrong precedent.
- **Two consecutive code-review rounds on Story 1.3 both found real, live-reproducible bugs** (a wiring bug, a duplicate-insert bug, a scroll-overflow bug) that pure code-reading and unit tests missed. This story's actual behavior (randomized selection) is unusually hard to verify by eye — lean harder on the deterministic-`Random`-injection testing approach in Dev Notes above rather than assuming "it compiles and the algorithm reads right" is sufficient.
- **Toolchain and emulator (`imagedrop_test`) already set up and working** — no environment setup needed. Note: the emulator's app data/install has been observed to reset unpredictably between sessions this project (not a code bug, an environment quirk) — don't be alarmed if a fresh Setup screen appears after a period of inactivity; just re-run through Setup as usual before continuing verification.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `gradle :app:compileDebugKotlin` — clean on every task's first attempt, no iteration needed.
- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL. Went from 50 → 63 tests across 9 classes (added `ImageSelectionEngineTest`: 8, `TransmissionRepositoryImplTest`: 3, extended `ImageRepositoryImplTest`: 6→8), 0 failures/errors/skipped — verified via `test-results/*.xml`, not just the gradle summary line.
- `gradle :app:lintDebug` — 0 errors, 20 pre-existing warnings (same count as before this story), 0 new ones from this story's code.
- `gradle :app:assembleDebug` — BUILD SUCCESSFUL.
- `gradle :app:connectedDebugAndroidTest` — all 16 instrumented tests passed on `imagedrop_test(AVD)` (was 14; added 2 new `AppDatabaseMigrationTest` cases: `migrate4To5_createsTransmissionsTable`, `migrate1Through5_succeedsAgainstAFreshV1Database`) — verified via `androidTest-results/connected/debug/*.xml` (`tests="16" failures="0" errors="0"`).
- **No live emulator UI verification for this story** — by design, per its own explicit scope boundary: this is a pure domain-algorithm story with no UI and no Worker wiring. `ImageSelectionEngine` is exercised directly by `ImageSelectionEngineTest`; there is nothing to click through on-device. `MIGRATION_4_5` was verified via `MigrationTestHelper` (deterministic, seeded) rather than a live reinstall-over-existing-data pass, since the new `transmissions` table has no prior data to preserve — the `MigrationTestHelper` coverage is the stronger verification here, not a lesser substitute.

**Code-review patch round (2026-07-11):**

- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL. Went from 63 → 69 tests across 9 classes (`ImageSelectionEngineTest` 8→14: added invalid-input-guard ×3, zero-active-images, Z-bounds-across-200-draws, since-boundary slot-capture; `TransmissionRepositoryImplTest` unchanged at 3, updated for the new `status` bound parameter), 0 failures/errors/skipped — verified via `test-results/*.xml`.
- `gradle :app:connectedDebugAndroidTest` — all 17 instrumented tests passed on `imagedrop_test(AVD)` (was 16; added 1 new `androidTest` `TransmissionRepositoryImplTest` exercising the real SQL query, including the `status` bound-parameter fix, against a seeded in-memory Room DB) — verified via `androidTest-results/connected/debug/*.xml` (`tests="17" failures="0" errors="0"`).
- Room schema export regenerated automatically (`exportSchema = true`) to reflect the renamed `index_transmissions_receiverId_status_sentAt` index; `5.json` confirmed updated on disk.

### Completion Notes List

- All 6 tasks implemented and covered by 13 new executed tests (8 `ImageSelectionEngineTest` + 3 `TransmissionRepositoryImplTest` + 2 new `ImageRepositoryImplTest` cases), all passing (63 unit tests total). `MIGRATION_4_5` verified via 2 new `MigrationTestHelper` cases (16 instrumented tests total).
- **Followed the story's own scope boundary strictly**: no `SendWorker`, no WorkManager wiring, no `ui/` changes, no `Transmission` write path (`insert`/`update` on `TransmissionDao`) — all explicitly deferred to Story 2.2, which the story file itself frames as normal sequencing, not a forward dependency. Resisted the temptation to "get ahead" while touching this code.
- **Mirrored `ComplianceGate`'s domain-service shape exactly**, per Dev Notes: `ImageSelectionEngine` is a plain class, constructor-injected with both repositories plus an injectable `Random` (defaulting to `Random.Default` in production). Every random draw inside `selectImagesFor` goes through the injected instance — no bare `Random.Default`/`.random()` calls anywhere in the algorithm.
- **Algorithm implementation simplified naturally from the 5 written steps to 4 lines of real logic**: once eligible-vs-fallback pool selection is correct, `pool.shuffled(random).take(z)` mechanically satisfies step 5 (starvation: `take(z)` on a smaller pool just returns what's there, no padding) without a separate special-cased branch. Verified this simplification doesn't skip any AC by testing all three branches (normal/fallback/starvation) explicitly rather than trusting the simplification by inspection alone.
- **Testing approach deliberately avoids fragile exact-random-sequence assertions.** Where a deterministic count was needed (exclusion/fallback/starvation branch tests), `minCount == maxCount` was used so `Z` is fixed regardless of which `Random` is supplied — sidesteps any assumption about exactly which `Random` method (`nextInt(until)` vs `nextInt(from,until)`) Kotlin's `shuffled()` calls internally. The one test that specifically needs `Z` to vary (re-roll-independently) uses a real fixed-seed `Random(42)` shared across 5 sequential calls and asserts the resulting sizes aren't all identical — deterministic (same seed always produces the same sequence) without asserting a specific fragile output.
- **Verification status, explicit:**
  - Executed and passing: all JVM unit tests (63 total, 13 new) and all instrumented tests (16 total, 2 new migration cases).
  - Verified via `MigrationTestHelper`: `MIGRATION_4_5` creates the `transmissions` table correctly and is insert/query-able; the full `1→5` migration chain succeeds from a fresh v1 database.
  - No UI/on-device interaction verification applicable — this story has no UI surface, confirmed by its own explicit scope boundary in the story file.
  - `Transmission`'s write path (insert `PENDING` rows, update `status`/`attemptCount`) remains genuinely unbuilt, not just untested — confirmed by re-reading `TransmissionDao.kt` before finalizing: only `getSentImageIdsSince` exists, exactly as scoped.
- **Code-review patch round (2026-07-11):** applied all 8 patch findings from the review — added an input-validation guard to `ImageSelectionEngine.selectImagesFor` (new `FailureReason.INVALID_INPUT`, rejects `minCount > maxCount`, negative counts, `maxCount == Int.MAX_VALUE`); changed `TransmissionDao.getSentImageIdsSince` to take `status` as a bound parameter instead of a hardcoded `'SENT'` literal, called with `TransmissionStatus.SENT.name`; widened the `transmissions` composite index to `(receiverId, status, sentAt)`; reworded the "repeats" comments in `ImageSelectionEngine` for clarity; added a Z-bounds test (200 draws), a since-boundary slot-capture test, a zero-active-images test, and a real-Room-DB instrumented test for `TransmissionRepositoryImpl`. Rebuilt and reran the full suite: 69 unit tests (was 63) and 17 instrumented tests (was 16), 0 failures/errors/skipped, both verified via XML. 7 findings deferred (documented in Review Findings below and in `deferred-work.md`), 2 dismissed as diff/NO_VCS artifacts.

### File List

**New:**
- `app/src/main/java/com/ris/imagedistributor/data/local/Transmission.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/TransmissionDao.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/TransmissionRepository.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/TransmissionRepositoryImpl.kt`
- `app/src/main/java/com/ris/imagedistributor/domain/ImageSelectionEngine.kt`
- `app/src/test/java/com/ris/imagedistributor/domain/ImageSelectionEngineTest.kt`
- `app/src/test/java/com/ris/imagedistributor/data/repository/TransmissionRepositoryImplTest.kt`

**Updated:**
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (added `Transmission` entity, version 4→5, `MIGRATION_4_5`; `MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4` untouched; patch round: renamed the `transmissions` index to include `status`)
- `app/src/main/java/com/ris/imagedistributor/data/local/ImageDao.kt` (added one-shot `getActive()`)
- `app/src/main/java/com/ris/imagedistributor/data/repository/ImageRepository.kt`/`ImageRepositoryImpl.kt` (added `getActiveImages()`)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (added `transmissionRepository`, `imageSelectionEngine`, registered `MIGRATION_4_5`)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (added `migrate4To5_createsTransmissionsTable`, `migrate1Through5_succeedsAgainstAFreshV1Database`)
- `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt` (added `getActiveImages()` success/failure cases)
- **Patch round additions:**
- `app/src/main/java/com/ris/imagedistributor/domain/AppResult.kt` (added `FailureReason.INVALID_INPUT`)
- `app/src/main/java/com/ris/imagedistributor/domain/ImageSelectionEngine.kt` (input-validation guard; reworded comments)
- `app/src/main/java/com/ris/imagedistributor/data/local/Transmission.kt` (composite index widened to include `status`)
- `app/src/main/java/com/ris/imagedistributor/data/local/TransmissionDao.kt` (`status` is now a bound parameter, not a hardcoded literal)
- `app/src/main/java/com/ris/imagedistributor/data/repository/TransmissionRepositoryImpl.kt` (passes `TransmissionStatus.SENT.name` to the DAO)
- `app/src/test/java/com/ris/imagedistributor/domain/ImageSelectionEngineTest.kt` (6 new tests: guard ×3, zero-active-images, Z-bounds, since-boundary)
- `app/src/test/java/com/ris/imagedistributor/data/repository/TransmissionRepositoryImplTest.kt` (updated for the new DAO signature)
- **New (patch round):**
- `app/src/androidTest/java/com/ris/imagedistributor/data/repository/TransmissionRepositoryImplTest.kt` (real-Room-DB integration test)

## Change Log

- 2026-07-11: Story implemented end-to-end (Tasks 1–6). New `Transmission` entity/DAO (read path only, per this story's explicit scope boundary), a one-shot `getActiveImages()` addition to `ImageRepository`, and `ImageSelectionEngine` implementing the CAP-3 algorithm exactly per mechanics.md's 5 steps, mirroring `ComplianceGate`'s domain-service shape with an injectable `Random` for deterministic testing. 13 new tests, all passing (63 unit + 16 instrumented, both counts verified via XML, not just BUILD SUCCESSFUL). `MIGRATION_4_5` verified via `MigrationTestHelper` (2 new cases). No UI/live-emulator verification needed or performed — this story has no UI surface by design. Status moved to `review`.
- 2026-07-11: Code-review patch round. Applied all 8 patch findings (input-validation guard, `status` bound parameter, widened composite index, comment reword, Z-bounds test, since-boundary test, zero-active-images test, real-DB instrumented test). 7 findings deferred to `deferred-work.md`, 2 dismissed as NO_VCS diff-construction artifacts. Full suite rerun and verified via XML: 69 unit tests (was 63), 17 instrumented tests (was 16), 0 failures/errors/skipped. Status moved to `done`.
