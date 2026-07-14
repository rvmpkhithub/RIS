# Sprint Change Proposal — Tag-on-Upload

## 1. Issue Summary

Story 1.4 (Image Tagging & List View) shipped and was marked `done`, but its scope deliberately left the upload flow itself untouched: uploading still uses a multi-select photo picker, and title/description entry only happens afterward, via the image's "View" button in the list. This was a documented assumption made during Story 1.4's own correct-course pass, when a clarifying question about the tagging entry point went unanswered and I proceeded with the lower-risk default (tag later, not tag-at-upload).

After using the shipped app, the operator confirmed this assumption was wrong on both counts:
- Upload should be **one image at a time**, not multi-select.
- Title/description should be **prompted immediately after picking the image**, before it's fully saved to the library — not deferred to a separate List → View round trip.

Both were confirmed directly via clarifying questions this session (see below).

## 2. Impact Analysis

- **Epic Impact:** Epic 1 (Image Library) only. No effect on Epic 2 (Distribution) or Epic 3 (Dashboard/Retention).
- **Story Impact:** Story 1.4 (`done`) is not reopened/edited — its shipped behavior (titled list, "View" → Image Detail, no thumbnails) is correct and stays. A new **Story 1.5: Tag-on-Upload** is added to revisit only the upload entry point, reusing Story 1.4's own `ImageDetailScreen` as the tagging surface immediately after a single-image upload.
- **Artifact Conflicts:** `SPEC.md` (CAP-1), `epics.md` (new Story 1.5), `DESIGN.md`/`EXPERIENCE.md` (Image Library IA description, Image detail view component note, Flow 1 journey text).
- **Technical Impact:** `ImageLibraryScreen.kt`'s upload button switches from `ActivityResultContracts.PickMultipleVisualMedia()` to the single-select `PickVisualMedia()` contract; after a successful `uploadImages` call for that one image, the app navigates directly into `ImageDetailScreen` for it (reusing Story 1.4's screen/ViewModel method, not a new screen). No schema, `ImageFileStore`, or active/inactive-toggle changes.

## 3. Recommended Approach

**Direct Adjustment** — add Story 1.5 within the existing Epic 1 backlog, following the same "story revisits a previously-shipped story's specific surface" pattern already established this session (Story 2.3 revisiting 1.3, Story 2.4 revisiting 2.1/1.3). No rollback, no MVP scope change — this is a UX-flow correction to a small, previously-ambiguous surface, not a redesign.

**Effort:** small — one screen's picker contract change, one new navigation call reusing existing UI, no new schema or entities. **Risk:** low — `ImageDetailScreen`/`updateImageDetails` are already shipped and tested; this story only changes how they're reached.

## 4. Detailed Change Proposals

### SPEC.md — CAP-1

OLD:
> Operator can upload a library of images, tag each with an optional title and short description, and flag each one active or inactive.

NEW:
> Operator uploads images one at a time; each upload prompts for an optional title and short description before the image is added to the library. Each image can be flagged active or inactive.

Rationale: reflects the confirmed one-at-a-time + tag-immediately requirement.

### epics.md — new Story 1.5

Added **Story 1.5: Tag-on-Upload** immediately after Story 1.4, with 3 ACs (single-select picker; immediate tag prompt after picking; skipping tag at upload still leaves the existing View/detail tagging path intact) and a technical note scoping it to `ImageLibraryScreen.kt`'s picker contract + reuse of the existing `ImageDetailScreen`/`updateImageDetails` flow.

Rationale: keeps Story 1.4's own shipped scope/ACs historically accurate; the fix is scoped as new work, matching this session's established amendment pattern.

### DESIGN.md / EXPERIENCE.md

- EXPERIENCE.md IA table, "Image Library" row: now notes upload is one-at-a-time with an immediate tag prompt.
- EXPERIENCE.md Flow 1 (Arjun's first evening): "uploads his first batch of images" → "uploads his first few images one at a time, tagging each with a short title as he goes"; incidentally also dropped a stale "3-6 daily image range" phrase left over from the min/max-count removal (Story 2.4), noticed while editing this line.
- DESIGN.md "Image detail view" component: noted the second entry point (immediately after a single-image upload, active defaults on, back action reads "Save") alongside the existing "View" entry point.

## 5. Implementation Handoff

**Scope: Minor.** Routed to: **Developer agent**, via the standard `create-story` → `dev-story` → `code-review` cadence. Story 1.5 is `backlog` in `sprint-status.yaml`, ready for `create-story` to pick up directly (single item in Epic 1's backlog).

**Success criteria:** Upload flow only ever offers single-image selection; picking one image immediately opens the tagging screen (reusing `ImageDetailScreen`) before returning to the list; declining to tag still saves the image as "Untitled," taggable later via the unchanged "View" path.
