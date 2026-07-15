---
baseline_commit: NO_VCS
---

# Story 1.5: Tag-on-Upload

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want to add one image at a time and be prompted to tag it immediately after picking it,
so that I don't have to upload first and separately hunt for it later just to add a title/description.

## Acceptance Criteria

1. Given I tap "Upload images", when the photo picker opens, then I can select only one image (not multiple) — a single-select picker, not the current multi-select one. [Source: epics.md#Story 1.5] [Source: sprint-change-proposal-2026-07-13-b.md#4. Detailed Change Proposals]
2. Given I've picked one image, when it's copied into the library, then I'm immediately shown a screen to enter an optional title and description for that image, before returning to the list. [Source: epics.md#Story 1.5] [Source: DESIGN.md#Components — image-detail-view]
3. Given I leave the title/description blank at upload time (or skip past them), when I later view the image via the existing "View" button on its list row, then it still shows as "Untitled" and can be tagged at any time via the existing Image Detail flow (unchanged) — this story does not remove or alter that path, only adds an earlier opportunity to tag. [Source: epics.md#Story 1.5]

**Post-ship amendment (2026-07-14, operator request during tablet field-testing):** AC2/AC3 are narrowed — Title is now **required** to complete a Save reached via the upload flow specifically (blank Title blocks Save with an inline "Title is required." error); Description remains optional in that flow. Editing an existing image later via the unchanged "View" path still allows a blank Title, exactly as AC3 originally specified — this preserves backward compatibility for already-"Untitled" images and matches the operator's request scope ("required on the image upload workflow"). Implemented via a new `ImagesRoute.Detail.requireTitleOnSave` flag (persisted across config changes/process death, unlike `preloaded`), threaded through `ImagesTab` into `ImageDetailScreen`'s new `requireTitle` parameter (default `false`, preserving the View path's original behavior untouched).

**Scope boundary:** this story revisits Story 1.4's shipped Image Library screen (`ImageLibraryScreen.kt` — replaces `ActivityResultContracts.PickMultipleVisualMedia()` with the single-select `PickVisualMedia()` contract) and reuses the existing `ImageDetailScreen`/`ImageLibraryViewModel.updateImageDetails` flow immediately after a successful upload, rather than requiring a separate List → View round trip. It does **not** change `ImageFileStore`, the `Image` entity/schema, the active/inactive toggle's behavior, or `ImageDetailScreen`'s own UI/layout in any way — the upload flow navigates into the *exact same, unmodified* screen "View" already uses.

## Tasks / Subtasks

- [x] Task 1: Single-image upload in the repository layer (AC: 1, 2)
  - [x] `data/repository/ImageRepository.kt`: replace `suspend fun uploadImages(uris: List<Uri>): AppResult<Unit>` with `suspend fun uploadImage(uri: Uri): AppResult<Long>` — returns the newly-inserted row's id on success (needed so the caller can navigate straight to that image's detail view). Update the interface doc comment accordingly.
  - [x] `data/repository/ImageRepositoryImpl.kt`: replace the `uploadImages` implementation (the `for (uri in uris) { ... }` loop) with a single-URI `uploadImage(uri: Uri): AppResult<Long>` that does the same copy-then-insert-with-cleanup-on-DB-failure logic for exactly one URI, returning `AppResult.Success(insertedId)` (from `dao.insert(...)`'s existing `Long` return) on success. Preserve the existing distinction between `FailureReason.FILE_ERROR` (copy failed) and `FailureReason.DATABASE_ERROR` (insert failed after a successful copy, with the orphaned file deleted via `fileStore.delete(...)`) — this logic doesn't change, only the "loop over N" wrapper around it goes away. **Do not touch** `observeImages()`/`setActive()`/`getActiveImages()`/`getImageById()`/`resolveFile()`/`updateImageDetails()`.

- [x] Task 2: Single-image upload in the ViewModel, with a race-free hand-off to the Detail screen (AC: 2)
  - [x] `ui/images/ImageLibraryViewModel.kt`: replace `fun onImagesPicked(uris: List<Uri>)` with:
    ```kotlin
    fun onImagePicked(uri: Uri?, onResult: (Image?) -> Unit) {
        if (uri == null) return // user backed out of the picker without selecting anything
        viewModelScope.launch {
            val uploadResult = repository.uploadImage(uri)
            val id = (uploadResult as? AppResult.Success)?.value
            val image = id?.let { (repository.getImageById(it) as? AppResult.Success)?.value }
            onResult(image)
        }
    }
    ```
    **Why the extra `getImageById` fetch, not just trusting the caller to find the new row in `images`:** `images` (this ViewModel's `StateFlow` from `observeImages()`) is backed by a Room `Flow` — its re-emission after this insert is asynchronous relative to `uploadImage`'s suspend return, so navigating to the Detail route immediately after `uploadImage` succeeds and relying on `images.find { it.id == newId }` has a real race window where the new row genuinely isn't in the current `images` snapshot yet. `getImageById` is a direct one-shot query (not reactive-list-dependent), so the `Image` object handed to the caller is always correct and available immediately — no race, no dependency on Flow timing. This is the same category of bug Story 1.4's own code review already found and fixed once (a `stillLoading` race); do not reintroduce an equivalent one here by wiring this differently.
  - [x] **Do not touch** `images`, `hasLoaded`, `isSaving`, `onToggleActive`, `resolveFile`, `updateImageDetails` — pure addition/replacement scoped to the upload path only.

- [x] Task 3: Thread the freshly-uploaded `Image` through to the Detail route without a race (AC: 2)
  - [x] `ui/images/ImagesRoute.kt`: change `data class Detail(val imageId: Long)` to `data class Detail(val imageId: Long, val preloaded: Image? = null)` — `preloaded` carries the just-uploaded `Image` directly from Task 2, sidestepping any dependency on `images`' Flow timing for this one navigation path. Update `ImagesRouteSaver`'s `save`/`restore` to keep encoding/decoding only `imageId` (same `"detail:$imageId"` string format, completely unchanged) — `preloaded` is deliberately **not** persisted across process death; on restoration, `Detail(imageId, preloaded = null)` is correct and safe, because by the time a saved-instance-state restoration happens, the image was already inserted in a *previous* app session, so it's unconditionally present in `images` by the time `hasLoaded` becomes true — the race Task 2 solves only exists in the few hundred milliseconds *within the same session*, immediately after the insert.
  - [x] `ui/images/ImagesTab.kt`: change `existing = images.find { it.id == current.imageId }` to `existing = current.preloaded ?: images.find { it.id == current.imageId }` — prefers the race-free preloaded value when present (the upload path), falls back to the existing reactive lookup otherwise (the "View" path, completely unchanged behavior). **Do not touch** `stillLoading`/`hasLoaded`, the `BackHandler`, or the `List` branch.

- [x] Task 4: Single-select picker + navigate straight to Detail after upload (AC: 1, 2)
  - [x] `ui/images/ImageLibraryScreen.kt`: change the picker launcher's contract from `ActivityResultContracts.PickMultipleVisualMedia()` to `ActivityResultContracts.PickVisualMedia()` (single-select — this is what actually satisfies AC1; the system picker itself will only allow one selection). The launcher's result callback becomes `onResult: (Uri?) -> Unit` instead of `(List<Uri>) -> Unit`:
    ```kotlin
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.onImagePicked(uri) { image -> if (image != null) onImageUploaded(image) } },
    )
    ```
    The `pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))` call itself is unchanged — `PickVisualMediaRequest` already only ever requested one image's worth of filtering, the multi/single distinction lives entirely in the *contract* (`PickMultipleVisualMedia` vs `PickVisualMedia`), not the request.
  - [x] `ImageLibraryScreen`'s signature gains a new parameter: `onImageUploaded: (Image) -> Unit` (called only on a successful upload — a cancelled picker or a failed upload does not navigate anywhere, matching this app's existing "only navigate on confirmed success" convention from every other save flow).
  - [x] `ui/images/ImagesTab.kt`: wire the new callback: `ImageLibraryScreen(viewModel = viewModel, onViewImage = { id -> route = ImagesRoute.Detail(id) }, onImageUploaded = { image -> route = ImagesRoute.Detail(image.id, preloaded = image) })`.
  - [x] **Do not touch** the empty-state/populated-state `LazyColumn` branch, `ImageListItem`, or anything below the picker button in this file.

- [x] Task 5: Tests (AC: 1, 2, 3)
  - [x] `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt` — rewrite the `uploadImages` test group for the new single-URI `uploadImage(uri): AppResult<Long>` signature. Cases: copies then inserts, returns `AppResult.Success(insertedId)` (stub `dao.insert(any())` to return a specific id, e.g. `7L`, and assert the result equals `AppResult.Success(7L)`); a file-copy failure maps to `FILE_ERROR` and never calls `dao.insert`; a DB-insert failure (after a successful copy) maps to `DATABASE_ERROR` **and** deletes the just-copied file via `fileStore.delete(...)`; does not swallow `CancellationException` from `fileStore.copyToAppStorage`. Remove `` `uploadImages attempts every uri even after an earlier one fails...` `` — there's no longer a multi-uri loop for that test to exercise.
  - [x] `app/src/test/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModelTest.kt` — rewrite the `onImagesPicked` test group for `onImagePicked(uri, onResult)`. Cases: a non-null `uri` calls `repository.uploadImage(uri)`, and on success calls `repository.getImageById(id)` and invokes `onResult` with that `Image` (stub both calls, assert `onResult` received the expected `Image`); a `null` uri does not call `repository.uploadImage` at all and does not invoke `onResult`; `repository.uploadImage` returning a `Failure` results in `onResult(null)` (and `getImageById` is never called in that case — `coVerify(exactly = 0)`).
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` and other migration tests: **no changes** — this story adds no schema changes.
  - [x] Check for an existing test file covering `ImagesTab`'s own routing (per this codebase's "extend, don't duplicate" convention and Story 1.4's own `ImagesRouteSaverTest.kt`) — if `ImagesRouteSaverTest.kt` exists, confirm its existing save/restore round-trip tests still pass unchanged against the new `Detail(imageId, preloaded)` shape (the `Saver` itself doesn't change what it encodes, only the data class gained a non-persisted field) — no new test needed there specifically for `preloaded`, since it's deliberately excluded from persistence.
  - [x] Full regression pass: `gradle :app:testDebugUnitTest` and `gradle :app:connectedDebugAndroidTest`, both verified via their XML results — confirm pre-existing test counts shrink only where this story explicitly removed a now-inapplicable test (documented above) and otherwise only grow, with 0 failures/errors.

- [x] Task 6: Live on-device verification (AC: 1, 2, 3)
  - [x] `installDebug` (no schema change this story — a fresh install/uninstall pass is not strictly required, but confirm the currently-installed build is up to date before verifying).
  - [x] Tap "Upload images" — confirm the system photo picker only allows selecting **one** image (no multi-select checkboxes/counter UI from the picker itself).
  - [x] Pick one image — confirm the app immediately shows the Image Detail screen for that exact image (full-bleed photo, Title/Description fields, Active toggle defaulting on), **without** first flashing the list screen or briefly bouncing back to it (the specific race Task 2/3 are designed to prevent — watch closely for this on the first live run).
  - [x] Enter a title (e.g. "Sunset"), tap Save — confirm it returns to the list and the row now shows "Sunset" instead of "Untitled".
  - [x] Repeat the upload flow with a second image, this time tapping the back action **without** entering a title — confirm it returns to the list and the row shows "Untitled" (skipping tagging at upload time is allowed, per AC3).
  - [x] Tap "View" on the "Untitled" row from the previous step — confirm the existing View → Image Detail flow still works completely unchanged (this proves AC3: the earlier tagging opportunity is additive, not a replacement for the existing path).
  - [x] Zero crashes throughout, confirmed via a final `logcat | grep "FATAL EXCEPTION"` sweep.

### Review Findings

- [x] [Review][Patch] Empty-state copy still said "upload some" (plural) despite the picker now being hard-limited to one image at a time [`app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryScreen.kt`:66] — a small leftover from the pre-1.5 multi-select copy. Fixed: changed to "upload one to get started."; updated the matching assertion in `ImageLibraryScreenTest.kt`. Verified via `connectedDebugAndroidTest` (67/67 passing).
- [x] [Review][Defer] A device rotation in the narrow window between a successful upload and the Room `images` Flow's re-emission of the new row drops `ImagesRoute.Detail.preloaded` (excluded from `ImagesRouteSaver` by design, justified only against process death — but `rememberSaveable` also round-trips on an ordinary config change). Falls through to `images.find` (which hasn't caught up yet), and since `hasLoaded` is already `true`, trips `ImageDetailScreen`'s "not found" branch, silently bouncing to List even though the upload succeeded. Deferred: the window is sub-second (Room emits near-instantly for local queries) and the consequence is bounded — the image is safely persisted, only the immediate tagging opportunity for that session is lost, recoverable via the existing "View" path. Consistent with this codebase's established tolerance for similarly narrow, non-data-losing race windows (e.g. Story 1.4's own deferred "stale in-flight save callback race").
- [x] [Review][Defer] `ImagesTab.kt`'s actual routing behavior (upload → lands on Detail with the right image, `BackHandler` returning to List, the `preloaded` fallback) has no automated Compose UI test — only `ImagesRouteSaverTest` exists, which covers pure string (de)serialization, not the composable's routing logic. Deferred: this exact behavior was directly and thoroughly live-verified in Task 6 (multiple `uiautomator dump` passes proving the preloaded hand-off, immediate Detail navigation, title save, skip-tag "Untitled" path, and the unchanged "View" path all work correctly) — a Compose UI test for `ActivityResultLauncher`-driven navigation would also be awkward to author (the picker result can't be easily mocked in a Compose test). A future improvement, not a blocking gap given the live-verification evidence already gathered.

## Dev Notes

- **The race-free `preloaded` hand-off (Tasks 2–3) is this story's one genuinely subtle piece — everything else is mechanical.** Do not simplify it away by having `ImagesTab` just navigate to `Detail(id)` and trust `images.find { it.id == id }` to find the row — it usually will (Room Flow re-emission is normally fast), but "usually" is exactly the kind of flaky-only-sometimes bug this codebase has already hit once this session (Story 1.4's `stillLoading` race, fixed in that story's own code review). Passing the already-known `Image` object directly via `preloaded` makes the correct behavior structurally guaranteed instead of timing-dependent.
- **`getImageById` already exists on `ImageRepository`/`ImageDao`, added for `SendWorker`'s use in Story 2.2 — Task 2 reuses it verbatim, no new query needed.**
- **This story does not touch `ImageDetailScreen.kt` at all.** AC3 is explicit that the existing View → Detail path is "unchanged" — the upload flow's job is only to *navigate into* that screen at a new moment (right after upload), not to modify what the screen does once it's there. If you find yourself editing `ImageDetailScreen.kt`, stop and re-read AC3.
- **`ActivityResultContracts.PickVisualMedia()` (singular) vs `PickMultipleVisualMedia()` (plural) is the entire mechanism behind AC1.** `PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)` — the *request* object passed to `.launch(...)` — is unaffected either way; it only filters to images, it was never what controlled single-vs-multi selection. Don't add any additional "only one at a time" logic beyond swapping the contract type; the system picker itself enforces the cardinality once you use the singular contract.
- **A cancelled picker (`uri == null`) or a failed upload must not navigate anywhere** — matches this app's established "only navigate on confirmed success" convention (compare `ReceiverEditScreen`'s Save only calling `onDone()` inside the success branch, never optimistically). `onImagePicked`'s `onResult` callback is only invoked with a non-null `Image` on genuine success; `ImageLibraryScreen` only calls `onImageUploaded` when that `Image` is non-null.
- **Do not add an `_isSaving`-style guard to `onImagePicked`.** Unlike `updateImageDetails`/`save`, there's no user-facing button that could be double-tapped to fire a second concurrent upload — the system photo picker is itself modal and can't be relaunched while already open, so this class of guard has nothing to protect against here.

### Previous Story Intelligence (from Story 1.4 and this session's Story 2.4)

- **Story 1.4's own code review found and fixed a `stillLoading`/race-condition bug in this exact `ImagesTab`/`ImageDetailScreen` pairing** (a hardcoded `stillLoading = false` caused a silent bounce-to-List on process-death restoration before `images` had loaded). This story introduces a *different* race in the same neighborhood (navigating to Detail immediately after an insert, before `images`'s Flow catches up) — Tasks 2–3's `preloaded` design is this story's fix for it, applied proactively rather than needing a second review round to catch it, mirroring how Story 2.3 proactively handled its own analogous fresh-install gap.
- **This codebase's "extend, don't duplicate" test-file convention applies to Task 5** — check for existing files/test groups before creating new ones.
- **`uiautomator dump` + parsing `bounds="[x1,y1][x2,y2]"` remains the reliable way to get exact tap coordinates for live verification.**
- **This session's toolchain remains**: `C:\Android\gradle\gradle-9.4.1` (via `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`), `C:\Android\sdk`'s `platform-tools`/`emulator`, `imagedrop_test(AVD) - 15` — no Gradle wrapper or Android Studio installation exists in this shell environment.

## Dev Agent Record

### Context Reference

<!-- Path(s) to story context XML/JSON will be added here by context workflow -->

### Agent Model Used

<!-- To be filled by dev-story -->

### Debug Log References

- `gradle :app:installDebug` — BUILD SUCCESSFUL, all build tasks UP-TO-DATE (implementation/unit-test work for Tasks 1–5 was already complete and verified in a prior session; this session's work was Task 6's live verification only).
- Live on-device verification against `imagedrop_test(AVD) - 15`, driven via `adb shell input`/`uiautomator dump` (screencap proved unreliable mid-session — see Completion Notes) plus direct sqlite3 queries against the installed app's database for ground-truth confirmation.
- Final sweep: `adb logcat -d | grep -i "FATAL EXCEPTION" | grep -i imagedistributor` — empty, zero crashes across the entire verification session.

### Completion Notes List

- **AC1 confirmed**: the photo picker opened via "Upload images" showed no multi-select checkboxes or "Add (N)" confirmation bar — single-tap-to-select-and-return behavior, confirmed via a clean `uiautomator dump` immediately after the picker opened (no pre-selected items, no bottom action bar).
- **AC2 confirmed**: picking a photo navigated straight to the Image Detail screen (no bounce through List), verified two independent ways — (1) a direct sqlite3 query against `image-distributor.db` showed each picked photo inserted as a new `images` row immediately, and (2) the Detail screen's Title/Description/Active/Save controls were present in the accessibility tree right after the picker closed. Entering a title ("sunset") and tapping Save returned to the list with the row showing "sunset" instead of "Untitled", confirmed via both the UI tree and a follow-up sqlite3 query.
- **AC3 confirmed**: backing out of the Detail screen without entering a title left that row as "Untitled" in the list; tapping "View" on it re-entered the same Image Detail screen via the pre-existing (Story 1.4) route, with all fields rendering correctly — the upload-time tagging opportunity is additive, not a replacement.
- **False-alarm investigation, worth recording**: early in this session's verification, the Detail screen appeared to render with only a "‹ Back" button and no Title/Description/Save fields at all, reproducing identically across a force-stopped/relaunched app and two separate `uiautomator dump`s taken 8 seconds apart. This looked like a real bug (AC2 failing silently) until scrolling the screen revealed the full form was present the whole time — the picked test photos were tall portrait screenshots (from this project's own prior testing sessions, present in the emulator's gallery), and `ImageDetailScreen`'s deliberate `ContentScale.FillWidth` full-bleed image rendering (by design, per this story's Dev Notes) made the image taller than one viewport, pushing the form below the fold. Not a defect — `screencap`'s screenshots were also separately unreliable in this session (intermittently capturing stale/composited frames, e.g. the Android recent-apps carousel instead of live app content), so all ground-truth verification in this pass was done via `uiautomator dump` (accessibility tree) and direct sqlite3 queries rather than trusting screenshots alone.
- No regressions, no crashes, no new deferred items — Tasks 1–5's implementation (from a prior session) held up correctly under live verification exactly as designed.

### File List

No source files changed in this session — Task 6 is verification-only. See prior session's implementation for the full file list (`ImageRepository.kt`/`ImageRepositoryImpl.kt`, `ImageLibraryViewModel.kt`, `ImagesRoute.kt`, `ImagesTab.kt`, `ImageLibraryScreen.kt`, plus their corresponding test files).

## Change Log

- 2026-07-13: Task 6 (live on-device verification) completed. All three ACs confirmed working end-to-end on `imagedrop_test(AVD) - 15`: single-select picker (AC1), immediate race-free navigation to Image Detail with a working title/description save (AC2), and unchanged skip-tagging + View-path behavior (AC3). Zero crashes. One false-alarm investigated and resolved (tall test-photo aspect ratio pushed Detail-screen fields below the fold — not a bug; see Completion Notes for the full diagnostic trail). Status moved to `review`.
