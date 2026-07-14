---
baseline_commit: NO_VCS
---

# Story 1.4: Image Tagging & List View

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the operator,
I want to give each image an optional title and short description, and browse my library as a titled list instead of a thumbnail grid,
so that I can identify images by what they are, not just by their appearance in a small square.

## Acceptance Criteria

1. Given I'm viewing an image in my library, when I open it, then I see a full-screen view of the image, its title and description fields, and the active/inactive toggle, with a way to edit and save the title/description. [Source: epics.md#Story 1.4] [Source: DESIGN.md#Components — image-detail-view]
2. Given an image has a title, when I view the image library list, then I see its title (or "Untitled" if none is set) and a "View" button, not a thumbnail. [Source: epics.md#Story 1.4] [Source: DESIGN.md#Components — image-list-item]
3. Given I tap "View" on an image's list row, when the detail view opens, then it shows the full image with its title/description editable in place. [Source: epics.md#Story 1.4] [Source: EXPERIENCE.md#Information Architecture — Image Detail]

**Scope boundary:** this story adds `title`/`description` (both nullable) to the `Image` entity, a new Image Detail screen, and replaces the Image Library screen's thumbnail grid with a titled list. It does **not** change the active/inactive toggle's behavior (Story 1.2's "toggle to inactive is immediate and excludes from future selection" rule is untouched — confirmed explicitly with the operator that the active flag itself is not being removed, only relocated out of a thumbnail-overlaid switch into a plain list-row/detail-view switch), does not change `ImageFileStore`/upload flow, and does not touch `ImageSelectionEngine` or anything in Epic 2 (that's Story 2.4's job — title/description are purely operator-facing metadata, never consulted by selection or delivery).

## Tasks / Subtasks

- [x] Task 1: `Image` entity + migration 8→9 (AC: 1, 2)
  - [x] `data/local/Image.kt`: add `val title: String? = null, val description: String? = null` to the `Image` data class, after the existing `uploadedAt` field. Both nullable — an image can have neither, either, or both set.
  - [x] `data/local/AppDatabase.kt`: bump `version` to `9`, add `MIGRATION_8_9`:
    ```kotlin
    /** Adds optional title/description tagging to images (Story 1.4) — purely additive, nullable columns, nothing to backfill for existing rows. */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `images` ADD COLUMN `title` TEXT")
            db.execSQL("ALTER TABLE `images` ADD COLUMN `description` TEXT")
        }
    }
    ```
    No `DEFAULT` clause needed — SQLite allows a nullable `ADD COLUMN` with no default, unlike the `NOT NULL` columns added in `MIGRATION_5_6`. **Do not touch `MIGRATION_1_2` through `MIGRATION_7_8`.**
  - [x] `di/AppContainer.kt`: add `AppDatabase.MIGRATION_8_9` to the `.addMigrations(...)` list (currently ends at `MIGRATION_7_8`).

- [x] Task 2: Repository layer — update image title/description (AC: 1)
  - [x] `data/local/ImageDao.kt`: add `@Query("UPDATE images SET title = :title, description = :description WHERE id = :id") suspend fun updateDetails(id: Long, title: String?, description: String?)`. **Do not touch** `observeAll()`/`insert()`/`setActive()`/`getActive()`/`getById()`.
  - [x] `data/repository/ImageRepository.kt`/`ImageRepositoryImpl.kt`: add `suspend fun updateImageDetails(id: Long, title: String?, description: String?): AppResult<Unit>`, following the exact `runCatchingDb` pattern already used by `setActive()` in the same file (`CancellationException` rethrown, generic `Exception` → `AppResult.Failure(FailureReason.DATABASE_ERROR)`). Trim blank strings to `null` at this layer (an empty-after-trim title/description is stored as `null`, not `""`) so the list/detail UI's "Untitled" fallback only needs to check for `null`, not both `null` and `""`.

- [x] Task 3: `ImagesRoute` sealed class + `ImagesTab` composable (AC: 1, 3)
  - [x] New file `ui/images/ImagesRoute.kt`, mirroring `ui/receivers/ReceiversRoute.kt` exactly:
    ```kotlin
    package com.ris.imagedistributor.ui.images

    /** Mirrors ReceiversRoute.kt's pattern — simple sealed-class routing, no navigation library. */
    sealed class ImagesRoute {
        data object List : ImagesRoute()
        data class Detail(val imageId: Long) : ImagesRoute()
    }
    ```
  - [x] New composable `ImagesTab(container: AppContainer)` (add to `ImageLibraryScreen.kt` or a new `ImagesTab.kt` — either is fine, follow whichever keeps the file under ~250 lines), mirroring `ReceiversTab`'s exact shape: a `rememberSaveable` route (using the same string-based `Saver` pattern — `"list"` / `"detail:$imageId"` — with the same malformed-string-falls-back-to-List safety net `ReceiversTab` already has), a `BackHandler(enabled = route is ImagesRoute.Detail) { route = ImagesRoute.List }`, and a `when` dispatching to `ImageLibraryScreen` (List) or the new `ImageDetailScreen` (Detail). The image for `Detail` is derived the same way `ReceiverEditScreen`'s `existing` param is derived in `ReceiversTab` — `images.value.find { it.id == imageId }` from the already-collected `ImageLibraryViewModel.images` list; if not found (deleted concurrently), bail back to `ImagesRoute.List` via a `LaunchedEffect`, same defensive pattern as `ReceiverEditScreen`'s `!isNew && existing == null` branch.
  - [x] `ui/App.kt`: replace the tab-0 case's direct `ImageLibraryScreen(viewModel = imageLibraryViewModel)` call with `ImagesTab(container = container)` — `ImagesTab` constructs its own `ImageLibraryViewModel` internally via `viewModel(factory = ...)`, same as `ReceiversTab` already does for `ReceiversViewModel`, so the `imageLibraryViewModel` local variable in `App.kt`'s tab-0 branch can be removed entirely.

- [x] Task 4: Image Library screen — grid replaced with a titled list (AC: 2)
  - [x] `ui/images/ImageLibraryScreen.kt`: change `ImageLibraryScreen`'s populated-state branch from `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 96.dp), ...)` to a `LazyColumn` with `verticalArrangement = Arrangement.spacedBy(8.dp)`, same `items(images, key = { it.id })` iteration.
  - [x] Replace the private `ImageGridItem` composable with `ImageListItem(image: Image, onToggleActive: (Boolean) -> Unit, onView: () -> Unit)` — **no `file: File` parameter, no `AsyncImage`, no `GrayscaleFilter`/`ColorMatrix`/`ColorPainter` — the whole point of this story is no thumbnail.** Row layout: `Text(image.title?.takeIf { it.isNotBlank() } ?: "Untitled", style = MaterialTheme.typography.titleMedium)` on the left, a `TextButton(onClick = onView) { Text("View") }` and the existing `Switch(checked = image.active, onCheckedChange = onToggleActive)` (with its existing `contentDescription = "Toggle active for image ${image.id}"` semantics, unchanged) on the right, wrapped in a `Card` with `{rounded.sm}` shape per `DESIGN.md#Components — image-list-item` (`{rounded.sm}` = `MaterialTheme.shapes.small`, matching the existing shape-token convention this codebase already uses). Inactive rows: reduce the title `Text`'s alpha (e.g. `.copy(alpha = 0.6f)` on its color) instead of the old desaturation overlay — there's no thumbnail left to desaturate.
  - [x] Remove now-unused imports: `androidx.compose.foundation.lazy.grid.*`, `androidx.compose.foundation.layout.aspectRatio`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.ColorFilter`/`ColorMatrix`, `androidx.compose.ui.graphics.painter.ColorPainter`, `androidx.compose.ui.layout.ContentScale`, `coil3.compose.AsyncImage`, `java.io.File` — none of these are needed once the thumbnail is gone. Keep `androidx.compose.foundation.background` only if still used elsewhere in the file (check before removing).
  - [x] Update the class-level doc comment (currently cites `DESIGN.md#Components (image-grid-item)`) to cite `image-list-item` instead.
  - [x] `ImageLibraryScreen`'s signature changes from `(viewModel: ImageLibraryViewModel)` to also accept `onViewImage: (Long) -> Unit` (called from `ImagesTab`, per Task 3).

- [x] Task 5: Image Detail screen (new) (AC: 1, 3)
  - [x] New file `ui/images/ImageDetailScreen.kt`. Signature: `ImageDetailScreen(viewModel: ImageLibraryViewModel, imageId: Long, existing: Image?, stillLoading: Boolean, onDone: () -> Unit)` — same `existing`/`stillLoading` shape `ReceiverEditScreen` already established for its own not-yet-loaded/deleted-concurrently handling; reuse that exact pattern (a `CircularProgressIndicator` while `stillLoading`, a `LaunchedEffect(Unit) { onDone() }` bail-out if `!stillLoading && existing == null`).
  - [x] Layout, per `DESIGN.md#Components — image-detail-view` ("full-bleed image, title/description as editable fields below it..., no gold-bordered card here"): a full-width `AsyncImage(model = viewModel.resolveFile(existing), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))` (or a fixed large height — either is fine, `ContentScale.Fit` avoids awkward cropping of a full photo), then below it (NOT inside a `BorderStroke(1.5.dp, GoldBorder)` card, per DESIGN.md's explicit "no gold-bordered card here" instruction — this is a deliberate exception to the pattern every other form screen in this app uses): a "Title" `OutlinedTextField`, a "Description" `OutlinedTextField` (allow multi-line: `singleLine = false`, a few visible lines), the active/inactive `Switch` (same `contentDescription = "Toggle active for image $imageId"` semantics as the list row), and a "Save" button.
  - [x] Save button behavior: same guarded-save shape as `SettingsViewModel.onSave`/`onSaveMasterSchedule` — calls `viewModel.updateImageDetails(imageId, title, description) { success -> if (success) onDone() else /* show inline error, e.g. "Couldn't save — please try again." */ }`. No confirmation toast on success — per `EXPERIENCE.md#Interaction Primitives`'s "banned: confirmation toasts for routine saves," returning to the list is the confirmation, same as every other save flow in this app.
  - [x] Text fields are pre-populated from `existing.title`/`existing.description` (empty string if `null`) using the same `remember { mutableStateOf(...) }` pattern `ReceiverEditScreen` uses for its own fields.

- [x] Task 6: `ImageLibraryViewModel` additions (AC: 1)
  - [x] Add `private val _isSaving = MutableStateFlow(false)` / `val isSaving: StateFlow<Boolean>` — same shared in-flight-save guard shape as `SettingsViewModel`/`ReceiversViewModel`.
  - [x] Add `fun updateImageDetails(id: Long, title: String?, description: String?, onResult: (Boolean) -> Unit)`, guarded by `_isSaving` exactly like `SettingsViewModel.onSave`: `if (_isSaving.value) return; _isSaving.value = true; viewModelScope.launch { try { val result = repository.updateImageDetails(id, title, description); onResult(result is AppResult.Success) } finally { _isSaving.value = false } }`.
  - [x] **Do not touch** `images`, `onImagesPicked`, `onToggleActive`, `resolveFile` — pure addition.

- [x] Task 7: Tests (AC: 1, 2, 3)
  - [x] `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt` (extend existing file): add cases for `updateImageDetails` — success delegates to `dao.updateDetails(id, title, description)`; a DB failure maps to `DATABASE_ERROR`; a blank (whitespace-only) title/description is trimmed to `null` before being passed to the DAO (verify via `coVerify { dao.updateDetails(id, null, null) }` when called with `"   "`/`""`).
  - [x] `app/src/test/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModelTest.kt` (extend existing file): add cases for `updateImageDetails` — success/failure reporting (same shape as `SettingsViewModelTest`'s `onSave` tests) and the `_isSaving` guard rejecting a concurrent second call.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (extend existing file): add `migrate8To9_addsTitleAndDescriptionColumns()` — seed a v8 `images` row, run `MIGRATION_8_9`, confirm the existing row survives with `title`/`description` both `NULL`, and confirm a newly-inserted row can supply real values for both. Extend the existing full-chain test to `migrate1Through9...` through `MIGRATION_8_9`.
  - [x] `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageLibraryScreenTest.kt` (extend existing file — the existing `showsEmptyStateWhenNoImages` test is unaffected, it never exercised the populated grid/list): add a case confirming a populated list shows each image's title (or "Untitled") and a "View" button, not a thumbnail; add a case confirming tapping the active/inactive `Switch` still calls through to `onToggleActive` (regression check that relocating the switch out of the grid didn't break its wiring).
  - [x] New `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageDetailScreenTest.kt`: a case confirming the screen shows the image's existing title/description pre-filled, editing and tapping Save calls through to the repository with the new values, and `onDone` fires only on a successful save (mirroring `ReceiversScreenTest`'s `editReceiverSucceedsWithZeroScheduleTimes`-style success/failure pair).
  - [x] Full regression pass: `gradle :app:testDebugUnitTest` and `gradle :app:connectedDebugAndroidTest`, both verified via their XML results, confirming pre-existing test counts only grow, 0 failures/errors.

- [x] Task 8: Live on-device verification (AC: 1, 2, 3)
  - [x] `adb uninstall` then fresh `installDebug` (a version-9 migration benefits from a clean-install verification pass, same established convention as every prior schema-changing story).
  - [x] Upload at least 2 images. Confirm the library shows a list (not a grid) with "Untitled" + a "View" button for each, and that the active/inactive toggle still works from the list row.
  - [x] Tap "View" on one image — confirm the full image displays, add a title and description, tap Save, confirm it returns to the list and the row now shows the new title instead of "Untitled".
  - [x] Re-open the same image's detail view — confirm the title/description persisted (survives a re-fetch, not just in-memory state).
  - [x] Toggle the image inactive from the detail view's switch, return to the list, confirm the list row reflects the same inactive state (both surfaces read the same underlying `Image.active` field).

### Review Findings

- [x] [Review][Defer] Active toggle on the Detail screen commits immediately to the DB while title/description edits are buffered and only committed on Save — pressing back after editing text (with or without touching the toggle) silently discards the text but keeps the toggle change, with no discard-confirmation. [`ui/images/ImageDetailScreen.kt:106-113`] — deferred, explicit operator decision: acceptable behavior for this internal single-operator tool, no discard-guard needed.
- [x] [Review][Patch] Detail screen image is not full-bleed, contradicting `DESIGN.md#Components — image-detail-view`'s explicit "full-bleed image" spec — the `AsyncImage` sits inside the same uniformly-24dp-padded `Column` as the text fields, using `ContentScale.Fit`/`fillMaxWidth()` rather than an edge-to-edge treatment. [`ui/images/ImageDetailScreen.kt:69-81`] — fixed: image moved outside the padded `Column` into its own full-width `Box`, switched to `ContentScale.FillWidth` (a fixed-height `Fit` still letterboxed portrait photos with empty space on the sides — caught during live verification and corrected to truly render edge-to-edge).
- [x] [Review][Patch] Detail screen has no on-screen back/close action — `DESIGN.md#Components — image-detail-view` explicitly calls for "a back action"; the current screen relies solely on the system/hardware back gesture. [`ui/images/ImageDetailScreen.kt`] — fixed: added a `‹ Back` text button overlaid top-start on the image (no icon library exists in this project — every other screen uses text buttons, not `Icons.*` — so this matches that established convention instead of adding a new dependency).
- [x] [Review][Patch] `stillLoading` is hardcoded to `false` in `ImagesTab`, so a not-yet-loaded image (e.g. `Detail` route restored via `rememberSaveable` after process death, before `viewModel.images` has emitted) is treated identically to a deleted one — the screen silently bounces back to List with no error. [`ui/images/ImagesTab.kt:48`] — fixed: `ImageLibraryViewModel` now exposes `hasLoaded: StateFlow<Boolean>` (set once `images` emits its first real value), and `ImagesTab` passes `stillLoading = !hasLoaded` instead of a literal `false`.
- [x] [Review][Patch] Detail screen's local `active` state can drift from the persisted value on a failed `setActive` call — no rollback or error shown, unlike the List row's `Switch`, which stays bound directly to the live `images` `StateFlow` and self-corrects. [`ui/images/ImageDetailScreen.kt:106-113`] — fixed: `onToggleActive` now accepts an optional result callback; the Detail screen's switch reverts to its previous value when the call fails.
- [x] [Review][Patch] List row's title `Text` has no `maxLines`/`overflow`, so an unusually long title can grow the row unpredictably. [`ui/images/ImageLibraryScreen.kt` — `ImageListItem`] — fixed: added `maxLines = 1, overflow = TextOverflow.Ellipsis`.
- [x] [Review][Patch] List row's "View" button and active-toggle `Switch` aren't semantically grouped with the row's title — with several "Untitled" rows on screen, a screen-reader user can't tell which View/switch belongs to which image. [`ui/images/ImageLibraryScreen.kt` — `ImageListItem`] — fixed: the "View" button now carries a `contentDescription` naming the row's title (e.g. "View Sunset").
- [x] [Review][Patch] No test exercises `ImagesTab`'s own routing/wiring — tapping View navigating to Detail, `BackHandler` returning to List, and the malformed-saved-state-falls-back-to-List safety net are all untested; only the two leaf screens are tested in isolation. [`ui/images/ImagesTab.kt`] — fixed: the route `Saver` was extracted to a named, directly-testable `ImagesRouteSaver` (`ui/images/ImagesRoute.kt`), with a new `ImagesRouteSaverTest.kt` covering the save/restore round-trip and the malformed-string-falls-back-to-List safety net. Full `ImagesTab` navigation (View→Detail, hardware-back→List) would require restructuring `ImagesTab` to accept an injectable `AppContainer`/repository, which is disproportionate to this patch round and matches this codebase's existing gap (`ReceiversTab` has no equivalent routing test either) — live on-device verification confirmed the actual navigation and back-button behavior work correctly.
- [x] [Review][Patch] `ImageDetailScreenTest`'s save-failure case doesn't assert that `isSaving`/the Save button re-enables after a failed save. [`app/src/androidTest/java/com/ris/imagedistributor/ui/ImageDetailScreenTest.kt:73-92`] — fixed: added `assertIsEnabled()` on the Save button after a failed save.
- [x] [Review][Defer] `updateDetails` ignores the DAO's affected-row count, so updating a since-deleted image's id reports `Success` though nothing changed. [`data/repository/ImageRepositoryImpl.kt:64-65`] — deferred, unreachable today: the app has no image-delete feature yet (same class of issue already deferred in Story 1.2's review for `setActive`).
- [x] [Review][Defer] Stale in-flight save callback race: pressing back mid-save and re-entering Detail before the stale `onResult` fires can force an unexpected route change back to List. [`ui/images/ImagesTab.kt`, `ui/images/ImageDetailScreen.kt`] — deferred, narrow timing window with no currently-exercised path.
- [x] [Review][Defer] Blank-to-`null` trimming for title/description is enforced only in `ImageRepositoryImpl`, not at the DAO/entity boundary. [`data/repository/ImageRepositoryImpl.kt:65`] — deferred, single call site today, matches this codebase's existing single-layer-normalization convention.
- [x] [Review][Defer] `ImageLibraryViewModel`'s shared `_isSaving` flag now spans both the List and Detail screens; no cross-screen conflict is reachable today. [`ui/images/ImageLibraryViewModel.kt:25-26`] — deferred, no live path exists yet.

## Dev Notes

- **This story is purely additive/UI-layer — it does not touch `ImageSelectionEngine`, `ImageFileStore`, the upload flow, or anything in Epic 2.** Title/description are never read by the selection algorithm or the delivery pipeline; they exist solely for the operator's own reference. If you find yourself touching `domain/ImageSelectionEngine.kt` while implementing this story, stop — that's Story 2.4's job, not this one.
- **The active/inactive toggle is relocated, not redesigned.** Its underlying behavior (`ImageRepository.setActive`, `ImageDao.setActive`, the immediate exclude-from-future-selection semantics from Story 1.2) is completely unchanged — only its visual container changes (from a thumbnail-overlaid `Switch` to a plain list-row/detail-view `Switch`). Confirmed explicitly with the operator during this story's correct-course pass that the active flag itself must not be removed.
- **Reuse the `ReceiversRoute`/`ReceiversTab` pattern exactly, don't reinvent it.** This app has no navigation library — every multi-screen "tab" (Receivers already, now Images) uses the same sealed-route + `rememberSaveable` + `BackHandler` shape. Deviating from this (e.g. introducing a different state-management approach for Images) would create an inconsistent second pattern for no benefit.
- **`title`/`description` trimming happens at the repository layer, not the UI layer** — `ImageRepositoryImpl.updateImageDetails` is the single place that decides "blank means null," so every caller (now just the Detail screen, potentially others later) gets the same normalization for free, consistent with this codebase's existing convention of putting normalization/validation at the boundary the domain relies on (compare `ReceiverEditScreen`'s trimming happening in the UI layer only because there's no repository-level equivalent need there — here, the "Untitled" fallback in the list needs a single, reliable definition of "no title," which only holds if blank-vs-null is normalized in exactly one place).
- **No gold-bordered card on the Image Detail screen** — this is a deliberate, explicit exception (per `DESIGN.md#Components — image-detail-view`) to the "form content sits on a bordered paper card" convention every other form screen in this app follows (`ReceiverEditScreen`, `RetentionPickerDialog`, `MasterSchedulePickerDialog` all use `BorderStroke(1.5.dp, GoldBorder)`). Do not add one here even though every other form-like screen has it — the reasoning is explicit in DESIGN.md: "this screen exists to look at the photo, not to feel like a form."
- **Migration 8→9 is simple (additive, nullable, no `DEFAULT` needed)** — do not overthink it or add unnecessary backfill logic. Contrast with `MIGRATION_5_6`'s `NOT NULL ... DEFAULT 0` columns (required because those were non-nullable); `title`/`description` being nullable means `ALTER TABLE images ADD COLUMN title TEXT` alone is valid SQLite, no default clause needed, no existing-row backfill needed (existing rows simply get `NULL` for both).

### Previous Story Intelligence (from Story 1.3 / Story 2.3's ReceiversRoute precedent)

- **The exact sealed-route + `rememberSaveable` + `BackHandler` pattern this story must replicate for Images lives in `ui/receivers/ReceiversRoute.kt` and `ui/receivers/ReceiversScreen.kt`'s `ReceiversTab` composable** — read both completely before writing `ImagesRoute`/`ImagesTab`; do not approximate the pattern from memory, copy its shape precisely (including the malformed-saved-state-falls-back-to-List safety net, which was itself a `[Review][Patch]` fix from a prior code-review round — worth preserving here from the start rather than needing a second review cycle to add it).
- **`ReceiverEditScreen`'s `existing`/`stillLoading` prop shape (nullable existing = not-yet-loaded-or-deleted, `stillLoading` boolean distinct from a genuinely-missing row) is the established pattern for "this screen represents one row from a list the parent already collected."** `ImageDetailScreen` should follow this exactly rather than inventing its own loading-state representation.
- **`SettingsViewModel.onSave`/`onSaveMasterSchedule`'s shared single `_isSaving` guard is this codebase's established pattern for "only one save operation in flight at a time, reported via a callback, no exception thrown across the ViewModel boundary."** `ImageLibraryViewModel.updateImageDetails` should follow this exactly.
- **This session's live-verification convention**: `adb uninstall` before `installDebug` for any schema-changing story remains mandatory (this story bumps the DB version to 9); use `C:\Android\gradle\gradle-9.4.1` (via `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`) and `C:\Android\sdk`'s `platform-tools`/`emulator` if no Gradle wrapper or Android Studio installation is found in the shell environment — this was the working toolchain discovered during Story 2.3's dev-story session and remains valid.
- **`uiautomator dump` + parsing `bounds="[x1,y1][x2,y2]"` is the reliable way to get exact tap coordinates for live verification** — visual estimation from a screenshot (even after accounting for the ~1.2x display-to-device scale factor) proved unreliable multiple times during Story 2.3's own live verification; go straight to `uiautomator dump` for any element that isn't trivially at a fixed, already-confirmed position.

## Dev Agent Record

### Context Reference

<!-- Path(s) to story context XML/JSON will be added here by context workflow -->

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- `gradle :app:compileDebugKotlin` — caught a real compile error mid-implementation (`ImagesTab.kt`'s `var route by rememberSaveable(...)` missing the `setValue` operator import — only `getValue` had been imported). Fixed by adding `import androidx.compose.runtime.setValue`; recompiled clean.
- `gradle :app:testDebugUnitTest` — BUILD SUCCESSFUL, verified via XML: 151 tests, 0 failures, 0 errors (was 144 before this story).
- `gradle :app:connectedDebugAndroidTest` (against `imagedrop_test(AVD) - 15`) — BUILD SUCCESSFUL, verified via XML: 63 tests, 0 failures, 0 errors (was 56 before this story).
- Live on-device verification: fresh `installDebug` (the app was not previously installed on the test emulator — `adb uninstall` reported `DELETE_FAILED_INTERNAL_ERROR`, confirmed via `adb shell pm list packages` that it genuinely wasn't installed, so this was already a clean-install state) → full Setup flow → confirmed every AC live (see Completion Notes).
- Same toolchain as Story 2.3/2.4-adjacent work: `C:\Android\gradle\gradle-9.4.1` via `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, `C:\Android\sdk`'s `platform-tools`/`emulator` (no Gradle wrapper or Android Studio install present in this shell environment).

### Completion Notes List

- All 8 tasks implemented and covered by 19 new/extended tests: 4 new `ImageRepositoryImplTest` cases, 3 new `ImageLibraryViewModelTest` cases, 2 new `AppDatabaseMigrationTest` cases (1 replacing the old full-chain test), 3 new/fixed `ImageLibraryScreenTest` cases, and a new `ImageDetailScreenTest` with 3 cases. 151 unit tests total (was 144), 63 instrumented tests total (was 56), all passing, verified via XML.
- **`ImagesRoute`/`ImagesTab` were built as new files** (`ui/images/ImagesRoute.kt`, `ui/images/ImagesTab.kt`) rather than folding `ImagesTab` into `ImageLibraryScreen.kt` — the story allowed either; a dedicated file kept `ImageLibraryScreen.kt` focused on just the list UI after its grid-to-list rewrite.
- **The schedule-time-list precedent (`ScheduleTimeListEditor`, Story 2.3) and the receiver-edit precedent (`ReceiverEditScreen`'s `existing`/`stillLoading` shape, `SettingsViewModel`'s `_isSaving` guard) were followed exactly, not reinvented** — `ImageDetailScreen` and `ImageLibraryViewModel.updateImageDetails` mirror these patterns precisely, per the story's own Dev Notes.
- **Live on-device verification, explicit, per AC:**
  - **AC2** (list view, no thumbnails): uploaded 2 images via the system photo picker — the library correctly rendered as a list (not a grid), each row showing "Untitled" + a "View" button + the active toggle, with no thumbnail image visible anywhere in the list.
  - **AC1 & AC3** (Image Detail, tagging): tapped "View" on the first row — confirmed the full image renders (via Coil `AsyncImage`, `ContentScale.Fit`), with Title/Description fields and the Active toggle below it (reached by scrolling — the image itself, being a full-resolution screenshot, occupies most of the initial viewport). Entered "Sunset" / "A peaceful evening sky", tapped Save — returned to the list, and the row updated from "Untitled" to "Sunset" immediately. Re-opened the same image's detail view and confirmed both fields were still populated with the saved values (proving real DB persistence via the migration 8→9 columns, not just in-memory state). Toggled the image inactive directly from the detail view's switch (immediate, no Save needed, matching `onToggleActive`'s existing semantics) — returned to the list and confirmed the row reflected the same inactive state (dimmed title, toggle off) — both surfaces read the same underlying `Image.active` field, exactly as the architecture requires.
  - Zero crashes throughout the entire verification session, confirmed via a final `logcat | grep FATAL EXCEPTION` sweep (empty result).
- **One live-verification wrinkle, not a bug**: an early tap on "View" appeared to open an unrelated screenshot/gallery view rather than the app's Image Detail screen. Investigation via `uiautomator dump` (checking the `package` attribute of the active window) confirmed this was a one-off stray tap/timing issue, not a real navigation bug — a retry with the same coordinates landed correctly on `com.ris.imagedistributor`'s own Image Detail screen, which then worked as expected for the remainder of verification.

### File List

**New:**
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImagesRoute.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImagesTab.kt`
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageDetailScreen.kt`
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageDetailScreenTest.kt`
- `app/src/test/java/com/ris/imagedistributor/ui/images/ImagesRouteSaverTest.kt` (review-round addition — covers the route `Saver`'s save/restore round-trip and malformed-string fallback)

**Updated:**
- `app/src/main/java/com/ris/imagedistributor/data/local/Image.kt` (added nullable `title`/`description`)
- `app/src/main/java/com/ris/imagedistributor/data/local/AppDatabase.kt` (version 8→9, `MIGRATION_8_9`; `MIGRATION_1_2`–`MIGRATION_7_8` untouched)
- `app/src/main/java/com/ris/imagedistributor/data/local/ImageDao.kt` (added `updateDetails`)
- `app/src/main/java/com/ris/imagedistributor/data/repository/ImageRepository.kt`/`ImageRepositoryImpl.kt` (added `updateImageDetails` with blank-to-null trimming)
- `app/src/main/java/com/ris/imagedistributor/di/AppContainer.kt` (registered `MIGRATION_8_9`)
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryScreen.kt` (grid replaced with a titled list; `ImageGridItem` replaced with `ImageListItem`; signature gained `onViewImage`; removed now-unused thumbnail-related imports; review-round: `maxLines`/`overflow` on the title, a descriptive `contentDescription` on the "View" button)
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModel.kt` (added `isSaving`, `updateImageDetails`; review-round: added `hasLoaded`, `onToggleActive` gained an optional result callback)
- `app/src/main/java/com/ris/imagedistributor/ui/App.kt` (tab-0 now renders `ImagesTab(container)` instead of constructing `ImageLibraryViewModel`/`ImageLibraryScreen` directly)
- `app/src/test/java/com/ris/imagedistributor/data/repository/ImageRepositoryImplTest.kt` (extended with `updateImageDetails` cases)
- `app/src/test/java/com/ris/imagedistributor/ui/images/ImageLibraryViewModelTest.kt` (extended with `updateImageDetails` cases)
- `app/src/androidTest/java/com/ris/imagedistributor/data/local/AppDatabaseMigrationTest.kt` (added `migrate8To9_addsTitleAndDescriptionColumns`; full-chain test renamed/extended to `migrate1Through9...`)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageLibraryScreenTest.kt` (updated existing test for the new `onViewImage` parameter; added 3 new tests)

**Review-round updates (code review patches):**
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImageDetailScreen.kt` (image moved to a full-bleed `ContentScale.FillWidth` `Box` outside the padded fields column; added a `‹ Back` text button overlaid on the image; active toggle now rolls back on a failed `setActive`)
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImagesTab.kt` (passes real `stillLoading = !hasLoaded` instead of a hardcoded `false`; route `Saver` now references the extracted `ImagesRouteSaver`)
- `app/src/main/java/com/ris/imagedistributor/ui/images/ImagesRoute.kt` (route `Saver` extracted to a named, unit-testable `ImagesRouteSaver`)
- `app/src/androidTest/java/com/ris/imagedistributor/ui/ImageDetailScreenTest.kt` (save-failure test now asserts the Save button re-enables)

## Change Log

- 2026-07-13: Story implemented end-to-end (Tasks 1–8). Added optional `title`/`description` tagging to images (additive migration 8→9), a new `ImagesRoute`/`ImagesTab` pair mirroring `ReceiversRoute`/`ReceiversTab`, a new Image Detail screen (full-bleed image, editable title/description, active toggle, no gold-bordered card per DESIGN.md's explicit exception), and replaced the Image Library's thumbnail grid with a titled list (title + "View" button + the same active/inactive toggle, just relocated). 19 new/extended tests, all passing (151 unit + 63 instrumented, both verified via XML). Live-emulator verification confirmed all three ACs end-to-end with screenshots: list view with no thumbnails, tagging a "Untitled" image to "Sunset" with a description, persistence surviving a re-open, and the active toggle staying in sync between the list and detail views. Zero crashes throughout, confirmed via logcat. Status moved to `review`.
- 2026-07-13: Code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed item resolved by the operator (silent-discard of unsaved title/description on back is acceptable, deferred). 8 patch findings applied: true full-bleed image (`ContentScale.FillWidth`, moved outside the padded fields column — a fixed-height `Fit` box was still letterboxing portrait photos, caught during live re-verification), an on-screen `‹ Back` action (text button, matching this app's icon-library-free convention), a real `hasLoaded` signal replacing the hardcoded `stillLoading = false` (fixes a silent bounce-to-List race on process-death restoration), rollback of the Detail screen's active toggle on a failed save, list-row title `maxLines`/ellipsis, a descriptive `contentDescription` on the "View" button, a save-failure re-enable assertion, and a new `ImagesRouteSaverTest` covering the route-restoration safety net. 1 Acceptance Auditor finding (claimed missing Detail-screen test coverage) was dismissed as a false positive caused by an incomplete diff excerpt given to that reviewer — `ImageDetailScreenTest.kt` already existed. 4 findings deferred as real-but-unreachable or pre-existing-pattern (logged in `deferred-work.md`). 156 unit tests + 63 instrumented tests, all passing, verified via XML. All patches re-verified live on-device (full-bleed image, back button, edit/save/persist round-trip). Status remains `review` pending final review-workflow completion.
- 2026-07-13: Post-ship fix (operator-reported, live device): the `‹ Back` button's plain white text on `ImageDetailScreen.kt` was invisible against light-toned photo content — no scrim behind it, so contrast depended entirely on what the underlying image happened to look like at that corner. Fixed by giving the button a semi-transparent black background (`Color.Black.copy(alpha = 0.45f)`, `MaterialTheme.shapes.small`), guaranteeing readable contrast regardless of image content. Compiles clean; not yet re-verified live on-device as of this entry.
