---
baseline_commit: NO_VCS
---

# Story 1.2: Image Library Management

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want to upload images and mark each one active or inactive,
so that I control exactly what can be sent.

## Acceptance Criteria

1. Given I'm on the image library screen, When I upload one or more images, Then they are copied into app-private internal storage and listed with an active/inactive toggle. [Source: epics.md#Story 1.2] [Source: SPEC.md#CAP-1]
2. Given an image is listed, When I toggle it to inactive, Then it is immediately excluded from future random selection, And toggling it back to active makes it eligible again, And an image already selected/queued for a send before being toggled inactive is unaffected — it still sends. [Source: epics.md#Story 1.2] [Source: SPEC.md#CAP-1] [Source: mechanics.md#Selection algorithm]

## Tasks / Subtasks

- [x] Task 1: `Image` entity, DAO, and schema migration (AC: 1, 2) — **extends the existing `AppDatabase`, does not create a second one**
  - [x] `data/local/Image.kt`: `id: Long` (autoincrement), `filePath: String` (relative filename per AD-14 — never absolute path or `content://` URI), `active: Boolean` (default `true`), `uploadedAt: Long` (epoch-millis). [Source: ARCHITECTURE-SPINE.md#AD-6, #AD-7, #AD-14]
  - [x] `data/local/ImageDao.kt`: `observeAll(): Flow<List<Image>>` (ordered by `uploadedAt` descending), `insert(image: Image): Long`, `setActive(id: Long, active: Boolean)`.
  - [x] Update `data/local/AppDatabase.kt`: add `Image::class` to `entities`, bump `version` to `2`, add `abstract fun imageDao(): ImageDao`. **Real data already exists on this device from Story 1.1** (the locked `ComplianceState` row) — write an explicit `Migration(1, 2)` that creates the `images` table; do not use `fallbackToDestructiveMigration()`. [Source: ARCHITECTURE-SPINE.md#AD-15] Verify the `CREATE TABLE` SQL matches what Room generates for the `Image` entity exactly (check the exported schema JSON under `app/schemas/` after building — `room.schemaLocation` is already configured in `app/build.gradle.kts`).
  - [x] Register the migration in `AppContainer`'s `Room.databaseBuilder(...)` call via `.addMigrations(MIGRATION_1_2)`.
- [x] Task 2: App-private image file storage (AC: 1)
  - [x] `data/local/ImageFileStore.kt` — one method, e.g. `suspend fun copyToAppStorage(uri: Uri): String` that opens `uri` via `ContentResolver.openInputStream`, writes it to `context.filesDir/images/<generated-filename>` (create the `images/` subdirectory if absent; generate the filename, e.g. `UUID.randomUUID()` + original extension), and returns the relative filename (not the full path) to be stored in `Image.filePath`. [Source: ARCHITECTURE-SPINE.md#AD-14]
  - [x] This is the *only* place that touches `context.filesDir` for images — `ImageRepository` calls it, nothing else does.
- [x] Task 3: `ImageRepository` (AC: 1, 2)
  - [x] `data/repository/ImageRepository.kt` (interface) + `ImageRepositoryImpl.kt` — wraps `ImageDao` and `ImageFileStore`. Every method returns `AppResult<T>` (catch and translate exceptions at this boundary — rethrow `CancellationException` first, same pattern as `ComplianceRepositoryImpl`). [Source: ARCHITECTURE-SPINE.md#AD-8] [Source: 1-1-first-run-registration-compliance-gate.md — `runCatchingDb` pattern to reuse/mirror, not reinvent]
  - [x] Methods: `observeImages(): Flow<List<Image>>`, `uploadImages(uris: List<Uri>): AppResult<Unit>` (copies each via `ImageFileStore` then inserts a row per successfully-copied file), `setActive(id: Long, active: Boolean): AppResult<Unit>`.
  - [x] No domain service needed here — this is simple CRUD, not a business rule `ImageSelectionEngine` (Epic 2) owns. ViewModel calls this repository directly, per AD-5's carve-out. [Source: ARCHITECTURE-SPINE.md#AD-5, #AD-10]
- [x] Task 4: `AppContainer` wiring (AC: 1, 2)
  - [x] Add `imageDao`, the `MIGRATION_1_2` registration (Task 1), an `ImageFileStore` instance, and an `imageRepository: ImageRepository by lazy { ... }` property to `di/AppContainer.kt` — follow the exact `by lazy` pattern already used for `complianceRepository`. [Source: di/AppContainer.kt — current file, read fully before editing]
- [x] Task 5: Image Library screen + ViewModel (AC: 1, 2)
  - [x] `ui/images/ImageLibraryViewModel.kt` — exposes `observeImages()` as `StateFlow`, `onImagesPicked(uris: List<Uri>)`, `onToggleActive(id: Long, active: Boolean)`. Obtained via a `ViewModelProvider.Factory` companion (mirror `SetupViewModel.factory(container)` exactly — do not go back to `remember { ViewModel(...) }`, Story 1.1's code review found that loses state across rotation). [Source: 1-1-first-run-registration-compliance-gate.md#Review Findings — "SetupViewModel instantiated via remember{} instead of ViewModelProvider"]
  - [x] `ui/images/ImageLibraryScreen.kt` — `LazyVerticalGrid` of image-grid-items. Upload entry point launches the **Android Photo Picker** (`rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia())`) — **not** a legacy `ACTION_GET_CONTENT`/storage-permission flow. The Photo Picker needs no runtime permission and is available back to API 26 via the AndroidX backport (already satisfied — `activity-compose` is pinned to 1.10.0, well above the 1.7.0 minimum for the backport). [Source: web research, 2026-07, see Latest Technical Notes below]
  - [x] Each grid item: square thumbnail loaded via **Coil** (`AsyncImage`, from the file in app-private storage), `{rounded.sm}` corners, an overlaid `Switch` bottom-right for active/inactive. Tapping the switch calls `onToggleActive` directly on the grid — no separate edit screen. [Source: DESIGN.md#Components — image-grid-item] [Source: EXPERIENCE.md#Component Patterns]
  - [x] Inactive images: desaturated + 60% opacity overlay. **No red icon, no "X" badge.** [Source: DESIGN.md#Components, #Do's and Don'ts]
  - [x] Empty state: `"No images yet — upload some to get started."` [Source: EXPERIENCE.md#State Patterns]
  - [x] Visual treatment matches the paper-card pattern established in Story 1.1's `SetupScreen`/`MainAppPlaceholder`: content area can reuse the same gold-`BorderStroke` `Card` styling where it groups content (e.g. the empty-state message), consistent with the DESIGN.md revision from Story 1.1's follow-up. [Source: DESIGN.md#Elevation & Depth, revised] [Source: ui/setup/SetupScreen.kt — reuse this exact `Card` pattern, don't reinvent]
- [x] Task 6: Wire into `App.kt`'s `MainAppPlaceholder` (AC: 1, 2)
  - [x] **This is an UPDATE, not a new file** — read `ui/App.kt` completely before editing (already loaded into this story's context; re-read the live file at implementation time in case it changed). Currently `MainAppPlaceholder` renders the same generic gold-bordered "not yet implemented" card for all 4 tabs via `tabs[selectedTab]`. Change only the `selectedTab == 0` (Images) case to render `ImageLibraryScreen`; **Receivers/Dashboard/Settings (indices 1–3) must keep their existing placeholder card exactly as-is** — those are Stories 1.3/3.1/3.2's job, not this one's.
  - [x] Preserve everything else in `App.kt` untouched: the routing `Box`/gradient background, `NavigationBar` styling, `Scaffold` transparency — this story only swaps one tab's content.

### Review Findings

**Context:** this review ran on 2026-07-12, well after the story shipped (2026-07-10) — five later stories had since touched some of the same shared files (`AppDatabase.kt`, `AppContainer.kt`, `App.kt`, and `ImageRepository`/`ImageRepositoryImpl` picked up `getActiveImages()`/`getImageById()`/`resolveFile()` for Stories 2.1/2.2/3.1). With no git history in this project, the adversarial/edge-case review scope was narrowed to this story's own 10 files (entity/DAO/file-store, repository, ViewModel, screen, and their tests); the shared "Updated" files were instead spot-checked directly (migration registration, DI wiring, tab-swap, Coil dependency) and found correct, with zero findings there.

- [x] [Review][Patch] `uploadImages` aborted the entire multi-image batch on the first failure — a failure on image N silently kept whatever 1..N-1 had already copied+inserted, never attempted N+1..end, yet reported the whole call as one blanket `Failure`. Confirmed by both Blind Hunter and Edge Case Hunter independently. Fixed by attempting every uri independently and aggregating any failure, so partial success is never silently abandoned and every image gets a real attempt. [data/repository/ImageRepositoryImpl.kt]
- [x] [Review][Patch] File-copy I/O failures were mislabeled as `DATABASE_ERROR`, the same catch-all used for genuine Room failures. Added a new `FailureReason.FILE_ERROR` and now catch `IOException` distinctly in `uploadImages`, mirroring the same multi-`FailureReason` pattern already established in `ComplianceRepositoryImpl`. [domain/AppResult.kt, data/repository/ImageRepositoryImpl.kt]
- [x] [Review][Patch] `ImageFileStore.copyToAppStorage` left a partially-written, orphaned file on disk if the copy failed partway through, and left an orphaned (complete) file if the DB insert that followed a successful copy then failed. Fixed with a try/catch-delete-rethrow in `ImageFileStore` for the mid-copy case, and an explicit `fileStore.delete(...)` call in `ImageRepositoryImpl` for the post-copy-insert-failure case. [data/local/ImageFileStore.kt, data/repository/ImageRepositoryImpl.kt]
- [x] [Review][Patch] `ImageFileStore.copyToAppStorage` performed blocking file I/O with no dispatcher switch — since it's invoked from `viewModelScope.launch` (defaults to `Dispatchers.Main.immediate`), a multi-image upload blocked the main thread for the duration of each copy, a real jank/ANR risk for larger images. Caught by the Acceptance Auditor. Fixed by wrapping the body in `withContext(Dispatchers.IO)`. [data/local/ImageFileStore.kt]
- [x] [Review][Patch] The always-visible "Upload images" button could render on top of the grid's last row, with no space reserved for it — the grid and button were both absolutely positioned in the same `Box` with no `contentPadding`/weight accounting for the button's height. Independently caught by Blind Hunter and the Acceptance Auditor, the latter tying it directly to AC1's "listed" requirement. Fixed by restructuring to a `Column` with the grid `weight(1f)` and the button as a normal sibling below it — the same fix shape this exact bug already got in this app's Dashboard screen. Verified live: with 3 uploaded images, the grid and the Upload button no longer overlap. [ui/images/ImageLibraryScreen.kt]
- [x] [Review][Patch] The per-image active/inactive `Switch` had no distinguishing accessibility label — EXPERIENCE.md's Accessibility Floor explicitly requires "every interactive element labeled with role + state," naming this exact toggle by name. With multiple images on screen, an unlabeled switch is indistinguishable from any other to TalkBack. Fixed by adding a `contentDescription` identifying which image's switch it is; the thumbnail's own `contentDescription` stays `null` (decorative — the switch already identifies the image). [ui/images/ImageLibraryScreen.kt]
- [x] [Review][Patch] Story 1.2's own `Migration(1,2)` had no dedicated `MigrationTestHelper` test — every other migration in this codebase (3→4, 4→5, 5→6, 6→7) has one; this was also the story's own disclosed gap ("skipped for time, confirmed correct only via one live install"). Added `migrate1To2_createsImagesTable()`, mirroring `migrate4To5_createsTransmissionsTable`'s exact shape. [androidTest — data/local/AppDatabaseMigrationTest.kt]
- [x] [Review][Patch] `ImageFileStore` — the one class in this diff doing raw file/stream I/O — had zero dedicated tests; it only ever appeared mocked out elsewhere. Added `ImageFileStoreTest.kt` (instrumented, real `Context`/file I/O): a successful-copy case, a no-orphaned-file-on-failure case, and a `delete()` case. [androidTest — data/local/ImageFileStoreTest.kt, new]
- [x] [Review][Patch] No test exercised the multi-uri partial-failure path (the exact scenario the first patch above fixes) — added a regression test proving every uri is still attempted after an earlier one fails, and the overall result still reports the failure. [test — data/repository/ImageRepositoryImplTest.kt]
- [x] [Review][Defer] No image-delete capability exists anywhere in the app — uploaded images (and their files) persist forever, with no user-facing way to reclaim storage or remove a mistaken upload. Deferred: the story's own ACs only ever asked for upload + active/inactive toggle, never delete — this is a real, permanent-accumulation product gap worth a future story's consideration, not a Story 1.2 implementation bug.
- [x] [Review][Defer] `ImageLibraryViewModel.onImagesPicked`/`onToggleActive` both silently discard the repository's returned `AppResult`, with no error state ever surfaced in the UI. Deferred: the screen's `StateFlow<List<Image>>` is fully reactive, so a failed operation simply doesn't change what's on screen rather than leaving it in a wrong state (an implicit, if unfriendly, failure signal) — no AC/design doc calls for explicit error UI, and building that (state + Snackbar/copy) is a UI feature addition beyond a bug fix.
- [x] [Review][Defer] `setActive` silently no-ops (0 rows affected) if the target id no longer exists. Deferred: currently unreachable in production, since nothing in this app ever deletes an `Image` row (see the delete-capability gap above) — ids can't go stale today. Revisit if/when delete is ever implemented.
- [x] [Review][Defer] No upload-in-progress/loading indicator during a multi-image upload. Deferred: real UX gap, but no AC/design doc calls for it.
- [x] [Review][Defer] No file-size or upload-count cap. Deferred: disproportionate for this single-operator, low-volume app; no spec calls for a limit.
- [x] [Review][Defer] The stored file extension defaults to `.jpg` on a failed/missing MIME lookup without validating actual file content. Deferred: self-assessed by the reviewer as "cosmetic today" since Coil sniffs by content, not extension — a latent landmine for hypothetical future extension-based logic, not a current bug.
- [x] [Review][Defer] `Image.filePath` has no DB index/uniqueness constraint, and `observeAll()` has no pagination. Deferred: proportionate for this app's expected scale (a personal image library); revisit if the library ever grows large enough to matter.
- [x] [Review][Defer] The instrumented `ImageLibraryScreenTest` only exercises the empty-state case — no grid/toggle/picker-launch coverage. Deferred: expanding Compose UI coverage for this screen is valuable but a substantial addition beyond this catch-up review's scope. Notably, this review discovered the story's own "won't execute in this sandbox" disclosure was stale — the existing test (and the rest of the instrumented suite) now runs cleanly in this environment.
- [x] [Review][Dismiss] "`dao.observeAll()`'s `.catch{}` makes a real DB error indistinguishable from an empty library" (Edge Case Hunter) — this is the exact, deliberate, established codebase-wide convention (`ComplianceRepositoryImpl.observeState`, `TransmissionRepositoryImpl.observeSentHistory` both do the identical `.catch { emit(fallback) }`), not a gap introduced by or specific to this story.
- [x] [Review][Dismiss] "Active/inactive is communicated only by desaturation + overlay, no redundant textual/iconic indicator" (Blind Hunter) — `DESIGN.md` explicitly, deliberately mandates "No red icon, no 'X' badge" for the inactive state (a Story 1.1 revision); adding a redundant icon/badge would violate this explicit design decision, not fix a gap.

**Live-emulator verification of the patches (2026-07-12):** cleared app data for a fresh install, ran through Setup, opened the Image Library (empty state renders correctly, no overlap). Tapped "Upload images" — confirmed via `dumpsys window`/logcat that the real Android Photo Picker actually launched (`mCurrentFocus` showed `PhotoPickerActivity`, logcat showed real picker item-loading activity) — this conclusively resolves the story's own long-disclosed "not conclusively verified" gap; the picker does work, a prior verification attempt just couldn't tell from a screenshot alone. Selected 3 images and confirmed upload end-to-end: all 3 rows appeared in the `images` table and all 3 files landed on disk (confirmed via direct `sqlite3`/`ls` against the real app data) with correct extensions (`.png`, matching the real MIME type — not the `.jpg` fallback). Confirmed the grid renders with no overlap against the Upload button (the layout fix). Toggled one image's switch off and confirmed the desaturation + 60%-opacity-overlay treatment renders correctly with no crash.

## Dev Notes

- **Paradigm reminder:** `ui/images` → `data/repository/ImageRepository` → `data/local` (`ImageDao`, `ImageFileStore`). No domain service layer for this story — CRUD only. [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-5]
- **Reuse, don't reinvent:** Story 1.1 already established the patterns this story must follow — the `AppResult`-wrapped repository with a `runCatchingDb`-style helper, the `ViewModelProvider.Factory` companion pattern for surviving rotation, and the gold-bordered `Card` visual treatment. Read `data/repository/ComplianceRepositoryImpl.kt`, `ui/setup/SetupViewModel.kt`, and `ui/setup/SetupScreen.kt` before writing this story's equivalents — mirror their shape, don't design new ones.
- **Schema migration is not optional for this story** — unlike Story 1.1 (which was the first schema, version 1, nothing to migrate from), this story's `AppDatabase` version bump (1 → 2) happens against a device that may already have a real locked `ComplianceState` row. Get the `Migration(1, 2)` right; a destructive fallback would wipe that row and re-trigger Setup, violating CAP-6's "no edit path" guarantee from Story 1.1. [Source: ARCHITECTURE-SPINE.md#AD-15]
- **Photo Picker, not legacy storage permissions:** do not request `READ_EXTERNAL_STORAGE`/`READ_MEDIA_IMAGES` or use `ACTION_GET_CONTENT`. The Android Photo Picker (`PickMultipleVisualMedia`) needs no runtime permission and already works down to this app's `minSdk = 26` via the AndroidX Activity library backport.
- **Testing:** JVM unit tests for `ImageRepositoryImpl` (mock `ImageDao`/`ImageFileStore`, same style as `ComplianceRepositoryImplTest`) and `ImageLibraryViewModel` (same style as `SetupViewModelTest`) — these can run and must actually pass in this environment. Compose UI tests for the grid (`androidTest`) follow the same pattern as `SetupScreenTest`/`ComplianceHaltScreenTest` — write them, but they will not execute in this sandbox (no emulator/device wired into the automated test run); state that explicitly in the Dev Agent Record rather than implying they were verified. Room migration correctness should get its own test using Room's `MigrationTestHelper` if time allows — flag as an open item in Completion Notes if skipped, don't silently omit it without saying so.

### Project Structure Notes

- Extends `com.ris.imagedistributor` structure exactly — no new top-level packages beyond `ui/images/`.
- Full file list this story creates:
  - `data/local/Image.kt`, `data/local/ImageDao.kt`, `data/local/ImageFileStore.kt`
  - `data/repository/ImageRepository.kt`, `data/repository/ImageRepositoryImpl.kt`
  - `ui/images/ImageLibraryViewModel.kt`, `ui/images/ImageLibraryScreen.kt`
- Files this story **updates** (read completely before editing, preserve everything not explicitly called out above):
  - `data/local/AppDatabase.kt` (add entity, bump version, add migration)
  - `di/AppContainer.kt` (add DAO/repository wiring)
  - `ui/App.kt` (swap only the Images tab's placeholder content)
  - `app/build.gradle.kts` / `gradle/libs.versions.toml` (add Coil dependency — see Latest Technical Notes)

### References

- [Source: SPEC.md#CAP-1]
- [Source: mechanics.md#Selection algorithm — "inactive only affects future selection, not what's already queued"]
- [Source: ARCHITECTURE-SPINE.md#AD-1, #AD-5, #AD-6, #AD-7, #AD-8, #AD-10, #AD-14, #AD-15]
- [Source: DESIGN.md#Components (image-grid-item), #Elevation & Depth (revised), #Do's and Don'ts]
- [Source: EXPERIENCE.md#Component Patterns, #State Patterns]
- [Source: epics.md#Epic 1, #Story 1.2]
- [Source: 1-1-first-run-registration-compliance-gate.md — established repository/ViewModel/Card patterns to mirror; its Review Findings on rotation-survival and AD-8 wrapping apply here too]

## Previous Story Intelligence (Story 1.1)

- **Toolchain already installed in this environment** — JDK 21, Android SDK (`C:\Android\sdk`), Gradle 9.4.1 (`C:\Android\gradle`), and an emulator (`imagedrop_test`, Android 15/Pixel 6) all exist from Story 1.1. No environment setup needed for this story.
- **AGP 9's built-in Kotlin + KSP (not KAPT)** is the established annotation-processing setup — Room's KSP processor is already wired in `app/build.gradle.kts`; just add the `Image` entity, no build-file plugin changes needed for Room itself.
- **Real bug class to watch for, found last story:** don't let a Room write's *timing* create a gap where an invariant can be violated (Story 1.1's bug was the registration lock only persisting after a network call succeeded). For this story, that maps to: make sure `Image` rows are inserted only after the file copy actually succeeds (don't insert a DB row pointing at a file that was never written) — `ImageRepositoryImpl.uploadImages` should copy-then-insert per image, not batch in a way that could leave dangling references.
- **Theme system was revised after Story 1.1 shipped** — `Theme.kt` now has `GoldBorder` and `appBackgroundBrush()` exposed publicly (see current file). New screens should use these, not invent their own colors.
- **Code review found real issues purely from actually reading the code carefully** (AD-8 gaps, the CancellationException swallow, rotation-survival) — none were caught by tests alone. Apply the same scrutiny while writing this story's code, don't rely solely on "it compiles and tests pass."

## Latest Technical Notes (web-verified 2026-07)

- **Coil 3.5.0** is current stable. Coordinates changed group in Coil 3: `io.coil-kt.coil3:coil-compose:3.5.0` (not the old `io.coil-kt:coil-compose`). Loading local app-private files needs only the core/compose artifacts — no network module required since nothing is fetched over HTTP here.
- **Android Photo Picker** (`ActivityResultContracts.PickMultipleVisualMedia`) requires `compileSdk`/`targetSdk` 33+ (already satisfied — this project is on 36) and is backported down to API 26 via the AndroidX Activity library (already satisfied — `activity-compose` 1.10.0 is well past the 1.7.0 minimum for the backport). No `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` permission needed; the picker grants temporary read URI access automatically. If the picker isn't available on a given device, the library falls back to `ACTION_OPEN_DOCUMENT` automatically — no extra code needed for that fallback.

- **Gotcha:** the Photo Picker grants only *temporary* read access to the returned `content://` URIs (valid for that launch/session). `ImageFileStore.copyToAppStorage` must read and copy each URI immediately in the same flow the picker result arrives in — don't store the picked URIs and defer copying to a later app session, that read will fail once the temporary grant expires.

Sources: [Photo picker (Android Developers)](https://developer.android.com/training/data-storage/shared/photo-picker), [Coil releases (GitHub)](https://github.com/coil-kt/coil/releases)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `gradle :app:compileDebugKotlin` — clean on first attempt (no iteration needed, unlike Story 1.1).
- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL. Went from 16 → 42 tests across 6 classes (added `ImageRepositoryImplTest`: 6, `ImageLibraryViewModelTest`: 5, `AppRouterTest` already existed), 0 failures/errors/skipped — verified via `test-results/*.xml`.
- `gradle :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug` — all BUILD SUCCESSFUL, 0 lint errors (same pre-existing `createComposeRule` deprecation warning as Story 1.1, not introduced by this story).
- Installed over the existing Story 1.1 app on the emulator (not a fresh install) specifically to exercise the `Migration(1, 2)` for real against a device with an already-locked `ComplianceState` row. **Confirmed working**: app launched straight past Setup into the main app (no data loss, no crash), and the real `ImageLibraryScreen` rendered with the empty-state gold-bordered card and teal "Upload images" button, matching the established theme.
- **Not confirmed**: tapping "Upload images" to verify the Android Photo Picker actually opens. Two tap attempts at estimated coordinates didn't visibly change the screen, and `dumpsys window` showed `MainActivity` still focused afterward — inconclusive whether this is a real issue or simply missed tap coordinates (no `ActivityNotFound` or crash in logcat either way, and a `PhotoPicker` background sync worker was observed running, which may be unrelated routine system activity rather than evidence the picker launched). This needs a real, deliberate tap-coordinate verification (or manual testing) before being called confirmed — flagging honestly rather than claiming it works.

**Code-review round (2026-07-12, ~21 months after original implementation):** three parallel review layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) ran against this story's own 10 files, scoped to exclude later stories' extensions to shared files (`getActiveImages`/`getImageById`/`resolveFile` for Stories 2.1/2.2/3.1). 9 patch findings applied, 7 deferred, 2 dismissed. The two most consequential fixes: `uploadImages` used to abort an entire multi-image batch on the first failure, silently keeping partial DB/file state while abandoning the remaining images and reporting one blanket failure — now every uri is attempted independently. And the always-visible "Upload images" button could render on top of the grid's last row with no space reserved for it — now a `Column`+`weight(1f)` layout (the same fix shape already used elsewhere in this app) guarantees no overlap. Also fixed: file-copy errors mislabeled as `DATABASE_ERROR` (added `FailureReason.FILE_ERROR`), orphaned files left behind on copy/insert failures (added cleanup), blocking file I/O on the main thread (added `Dispatchers.IO`), and a missing accessibility label on the per-image toggle (EXPERIENCE.md explicitly requires this exact one). Added the missing `Migration(1,2)` test and a new `ImageFileStoreTest.kt` — both explicitly disclosed gaps in this story's original Dev Notes. `gradle :app:testDebugUnitTest` — 136 tests (was 134 from the prior story's own count baseline), 0 failures. `gradle :app:connectedDebugAndroidTest` — 40 tests (was 36), 0 failures — critically, this run finally executed `ImageLibraryScreenTest`, which the story's original Dev Notes disclosed as "won't execute in this sandbox"; that disclosure turned out to be stale, not a persistent limitation. **Live-emulator verification also finally resolved the story's other original disclosed gap** ("Photo Picker interaction not conclusively verified"): confirmed via `dumpsys window`/logcat that the picker genuinely launches (`mCurrentFocus` = `PhotoPickerActivity`), then completed a real 3-image upload end-to-end, confirmed via direct `sqlite3`/`ls` against the actual app data that all 3 rows and files landed correctly, confirmed the layout fix (no grid/button overlap), and confirmed the active/inactive toggle's desaturation treatment. Both of the story's two behavior-verification gaps and its one test-coverage gap are now closed; the third (delete capability) was determined to be out of this story's original scope, not a gap, and is instead tracked as a deferred future-story candidate. Full findings in the Review Findings section above; deferred items also logged to `deferred-work.md`. Status moved to `done`.

### Completion Notes List

- All 6 tasks implemented and covered by 11 new executed unit tests (6 `ImageRepositoryImplTest` + 5 `ImageLibraryViewModelTest`), all passing. `AppDatabase` migrated cleanly from v1→v2 against a real on-device install with existing data — the single highest-risk item flagged in this story's Dev Notes.
- **Story 1.1 lesson applied**: `uploadImages` copies each file *then* inserts its DB row, per-image (not batched), specifically to avoid ever having a DB row point at a file that was never written — covered by `ImageRepositoryImplTest`'s ordering test.
- **Verification status, explicit:**
  - Executed and passing: all JVM unit tests (11 new + 31 pre-existing = 42 total).
  - Verified live on-device: schema migration correctness, empty-state screen rendering, theme consistency.
  - **Not verified**: the Photo Picker upload flow end-to-end (launching the picker, selecting an image, seeing it appear in the grid, toggling active/inactive visually). The code compiles and follows the documented API correctly, and the empty-state/upload-button UI is confirmed rendering, but the actual picker interaction was not confirmed working in this session — this is a real gap, not a formality, and should be manually verified (or retried with accurate tap coordinates / a physical device) before fully trusting AC1's upload path.
  - Instrumented Compose UI test (`ImageLibraryScreenTest`) compiles but did not execute — same sandbox limitation as Story 1.1 (no wired-in device for automated instrumented runs).
  - Room `Migration(1,2)` has no dedicated `MigrationTestHelper` unit test — skipped for time, confirmed correct only via the live on-device install described above. Flagging as a real open item rather than silently omitting it.
- **Code-review round verification status (2026-07-12) — all three gaps above are now closed:**
  - The Photo Picker upload flow IS confirmed working end-to-end: live-verified via `dumpsys window` (`PhotoPickerActivity` genuinely focused), a real 3-image selection and upload, and direct `sqlite3`/`ls` confirmation that all 3 rows/files landed correctly. Also confirmed the active/inactive toggle visually (desaturation + overlay) and the new no-overlap grid/button layout.
  - `ImageLibraryScreenTest` now executes and passes — the "won't execute in this sandbox" limitation was stale, not persistent; the full instrumented suite (40 tests) runs cleanly in this environment.
  - `Migration(1,2)` now has a dedicated `MigrationTestHelper` test (`migrate1To2_createsImagesTable`), matching every other migration's coverage in this codebase.
  - 9 patch findings from the 3-layer review applied (partial-upload-batch bug, mislabeled file-copy errors, orphaned files on failure, main-thread file I/O, grid/button overlap, missing accessibility label, plus the two disclosed test gaps above), 7 deferred (real but out-of-scope or disproportionate — see `deferred-work.md`), 2 dismissed (matching established codebase conventions). Full suite rerun and verified via XML: 136 unit tests, 40 instrumented tests, 0 failures/errors.

### File List

**New:**
- `app/src/main/java/com/ris/imagedistributor/data/local/Image.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/ImageDao.kt`
- `app/src/main/java/com/ris/imagedistributor/data/local/ImageFileStore.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/ImageRepository.kt`
- `app/src/main/java/com/ris/imagedistributor/data/repository/ImageRepositoryImpl.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModel.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryScreen.kt`
- `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt`
- `app/src/test/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModelTest.kt`
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageLibraryScreenTest.kt` (instrumented — confirmed executing as of the code-review round)

**Updated:**
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (added `Image` entity, version 1→2, `MIGRATION_1_2`)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (added `imageFileStore`, `imageRepository`, registered the migration)
- `app/src/main/java/com/ris/imagedistributor/ui/App.kt` (Images tab now renders `ImageLibraryScreen`; Receivers/Dashboard/Settings placeholders untouched)
- `gradle/libs.versions.toml`, `app/build.gradle.kts` (added Coil 3.5.0)

**Code-review patch round additions (2026-07-12):**
- `app/src/main/java/com/ris/imagedistributor/domain/AppResult.kt` (added `FailureReason.FILE_ERROR`)
- `app/src/main/java/com/ris/imagedistributor/data/local/ImageFileStore.kt` (`copyToAppStorage` now runs on `Dispatchers.IO` and cleans up on failure; added `delete(filePath)`)
- `app/src/main/java/com/ris/imagedistributor/data/repository/ImageRepositoryImpl.kt` (`uploadImages` now attempts every uri independently instead of aborting on the first failure, distinguishes `FILE_ERROR` from `DATABASE_ERROR`, and cleans up an orphaned file if the DB insert fails after a successful copy)
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryScreen.kt` (grid + Upload button restructured as `Column`+`weight(1f)` siblings instead of overlaid `Box` children; added a `contentDescription` to the per-image `Switch`)
- `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt` (added a DB-insert-failure-cleanup test and a multi-uri partial-failure regression test; updated the file-copy-failure test to expect `FILE_ERROR`)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (added `migrate1To2_createsImagesTable`)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/ImageFileStoreTest.kt` (new — successful-copy, no-orphaned-file-on-failure, and `delete()` cases)

**Follow-up fix (2026-07-12, post-review):**
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryScreen.kt` (`AsyncImage` given a solid-color `placeholder`/`error` painter to prevent a transient grid misalignment during Coil's async load right after a fresh upload)

## Change Log

- 2026-07-10: Story implemented end-to-end (Tasks 1–6). Schema migration verified live against an existing on-device install (no data loss). 11 new unit tests, all passing (42 total). Photo Picker upload interaction not conclusively verified in this session — flagged as an open item, not claimed as working. Status moved to `review`.
- 2026-07-12: Code-review round, run ~21 months after original implementation (the story had been sitting at `review` status with no review ever having been run). Three parallel review layers found 9 patch findings, applied all of them — most consequentially, `uploadImages` used to abort an entire multi-image batch on the first failure (now every uri is attempted independently), and the "Upload images" button could overlap the grid's last row (now a `Column`+`weight(1f)` layout guarantees no overlap). Also fixed mislabeled file-copy errors, orphaned files on copy/insert failure, main-thread-blocking file I/O, and a missing accessibility label EXPERIENCE.md explicitly requires. Closed all three of the story's own originally-disclosed gaps: added the missing `Migration(1,2)` test, added `ImageFileStoreTest.kt`, and live-verified the Photo Picker upload flow end-to-end (previously "not conclusively verified," now confirmed via `dumpsys window`/logcat plus a real 3-image upload checked directly against the app's DB and filesystem) — also discovered the instrumented test suite's "won't execute in this sandbox" disclosure was stale, not persistent. 7 findings deferred (real but out-of-scope or disproportionate; logged to `deferred-work.md`), 2 dismissed (matching established codebase conventions). Full suite rerun and verified via XML: 136 unit tests, 40 instrumented tests, 0 failures/errors. Status moved to `done`.
- 2026-07-12 (follow-up): User-reported UX issue after the review round — right after a fresh multi-image upload, the grid could briefly render with some cells showing no visible thumbnail and their switch appearing misaligned (floating outside/below where the image should sit), self-correcting a moment later once Coil finished loading. Root cause: `AsyncImage` had no `placeholder`/`error` painter, so a still-loading (or failed) cell briefly had no content to size itself by, which could momentarily disturb the surrounding grid row's layout. Fixed by giving `AsyncImage` a solid-color placeholder/error `ColorPainter` (the theme's `surfaceVariant`) so every cell always has consistent square content to lay out against, even mid-load. Reproduced the original glitch live with fresh test images, applied the fix, and reproduced the exact same upload again — grid now renders correctly aligned immediately, with no staggering. Full suite rerun: 136 unit tests, 40 instrumented tests, 0 failures; lint clean. This also incidentally resolves the "`AsyncImage` has no error/placeholder composable" gap Story 3.1's own review had identified as pre-existing in this exact file ("the exact pre-existing convention already used by `ImageLibraryScreen`'s `ImageGridItem`") — that gap actually originated here, not in Story 3.1's own diff.
