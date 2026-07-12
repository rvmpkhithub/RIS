# Review: ARCHITECTURE-SPINE.md (image-distributor-app)

Reviewed against: good-spine checklist (divergence coverage, AD enforceability, deferred-list
soundness, tech-currency sanity check, capability coverage, structural-dimension completeness,
terseness). Cross-referenced against SPEC.md and mechanics.md.

## Verdict

Solid, terse spine with well-chosen, mostly-enforceable ADs and full CAP-1..8 coverage — but it
has one real contradiction with its own companion doc (compliance-state caching) and one
contradiction with SPEC's stated datastore, plus a couple of silently-skipped structural
decisions (schema migration, image-file storage location) that meet the bar for "could let two
independently-built units diverge in a way that matters at this scale."

---

## 1. Divergence points fixed vs. missed

Covered well: layering, single egress per external API, config centralization, WorkManager-only
background execution, repository-as-mutation-gate, ID scheme, timestamp representation, error
shape, update mechanism, business-rule ownership. These are the ADs that matter most for a
single-developer/no-backend app and each maps to a plausible real bug class.

**Missed / silently skipped:**

- **Room schema migration strategy.** AD-9 commits the project to shipping new versions as a
  manually reinstalled APK over an existing install (not a fresh uninstall). That means the Room
  database persists across versions and schema changes must be handled — either real
  `Migration`s or `fallbackToDestructiveMigration()`. Neither is decided nor deferred. Two builds
  months apart (by the same or a different implementer) could pick different strategies; one
  crashes on upgrade, the other silently wipes the transmission ledger the dashboard depends on
  (CAP-5) and the compliance/nickname lock (CAP-6, which is supposed to be a *permanent* lock).
  This is exactly the kind of thing this altitude should own and doesn't.

- **Uploaded image file storage location/strategy.** CAP-1 requires uploading and persisting an
  image library; the `IMAGE` entity has only `filePath: string`. Whether images are copied into
  app-private storage (survives only until uninstall, no permission needed) vs. referenced via a
  `content://` URI/MediaStore (needs persisted URI permission grants, survives differently) is a
  real behavioral fork with implications for AD-9's reinstall-based update path (if the operator
  ever has to uninstall/reinstall — e.g. after a keystore change, which is explicitly deferred —
  app-private images vanish). Not decided, not deferred, not flagged as an open question.

- **Backup/data-loss recovery for the single device.** All state (image library, up to 10
  receivver configs, 30+ days of transmission history used to resolve billing disputes) lives on
  one tablet with no mention of backup, export, or recovery path anywhere in the spine or the
  Deferred section. Given the "resolve a customer's dispute" success criterion (CAP-5) hinges on
  this data surviving, its complete absence — not even an explicit "out of scope" line — is a gap
  at the operational-envelope level the checklist calls out.

## 2. AD enforceability — spot check

All 10 ADs pass the "actually prevents its stated divergence" test: each names a concrete file/
folder/type that is the single legal location for something, which is checkable by inspection or
a simple lint/ArchUnit rule. None are decorative. AD-4's battery-optimization-exemption clause is
worth calling out positively — it's a real, non-obvious fix for the actual failure mode (OEM
battery managers killing WorkManager jobs) rather than just naming the API.

One soft spot: **AD-3 doesn't state where the WhatsApp token or SMTP credential actually live at
rest** ("token reference" is ambiguous — a literal secret string in `AppConfig.kt` compiled into
a sideloaded, easily-decompiled APK, or an indirection to something else?). Given both this app's
own compliance API and the SPEC explicitly accept no-auth/hardcoded credentials as fine for this
trust context, this is likely an accepted risk rather than an oversight — but the spine should say
so explicitly (one line) rather than leave "token reference" ambiguous, since it's the one spot
where two builders could genuinely diverge (one hardcodes the literal secret, another wires up a
never-specified secure-storage mechanism that doesn't exist elsewhere in this stack).

## 3. Deferred list — soundness check

All five deferred items are legitimately safe to defer at this scale (signing/keystore, test
framework choice, WhatsApp template content, analytics, multi-tenancy) — none of them, as
deferred, allow two builds of the *core CAP-1..8 behavior* to diverge. Reasonable.

However, as noted in §1, two items that should be in this list (or decided) are simply **absent**:
Room migration strategy and image-file storage location. Their absence is the main defect in this
section — not that something was wrongly deferred, but that two real decisions never appear at
all.

## 4. Critical contradiction: `COMPLIANCE_STATE` entity vs. mechanics.md's explicit "no caching" rule

This is the most important finding.

- `mechanics.md` (Compliance flow, step 7) is explicit: *"No local caching of the compliance
  result is needed — the 'fail open unless explicitly false' rule makes a cache unnecessary. (An
  earlier draft of this design cached the result for 2 days; that was superseded by the
  retry-based rule above **and should not be built**.)"*
- The spine's ER diagram nonetheless defines:
  ```
  COMPLIANCE_STATE { long id, string nickname, string city, bool isCompliant, long lastCheckedAt }
  ```
  `isCompliant` + `lastCheckedAt` is precisely the shape of the rejected 2-day cache. AD-10 says
  `ComplianceGate` is the "only place" the fail-open rule is implemented, but never clarifies what
  `ComplianceState.isCompliant` is *for* if it isn't gating sends — it just as easily reads as "the
  last known answer, used when the API is unreachable," which is the opposite of the fail-open
  rule SPEC/mechanics mandate (CAP-7: unreachable ⇒ keep sending, not "consult the last cached
  value").
- This is a genuine, high-risk divergence point: one implementer builds `ComplianceGate` to always
  call the live API and only ever *write* to `ComplianceState` for display purposes (dashboard/
  "contact admin" screen), correctly matching mechanics.md; another reads the ER diagram first and
  builds the gate to read `isCompliant`/`lastCheckedAt` as a fallback/cache when the network call
  fails — silently reintroducing the rejected design and inverting the fail-open guarantee that is
  this app's single most safety-critical rule (accidentally halting a paying operator's sends, or
  the reverse — never noticing a real non-compliant flip because a stale cached `true` is trusted).
- **Recommendation:** Add a one-line rule (or extend AD-10) stating `ComplianceState` is a
  read-model/audit record only, written after every check for display purposes, and is never
  consulted by `ComplianceGate` to decide whether to send — the gate's decision is always: explicit
  `false` response → halt, anything else (including no response) → continue.

## 5. SPEC vs. spine datastore mismatch

SPEC.md's Constraints section states: *"App data persists in a local **PostgreSQL** database
only; no cloud database dependency."* The spine (frontmatter scope, Design Paradigm, Stack table,
Structural Seed diagram) uses **Room over SQLite** throughout, with no note reconciling this. Room/
SQLite is almost certainly correct (there is no realistic embedded-PostgreSQL story for a sideloaded
Android app) and this reads like a SPEC authoring slip rather than a real architectural choice —
but as written, the spine silently overrides its own binding source document without a documented
rationale. Given SPEC.md is described as the "canonical contract," this should either be corrected
upstream in SPEC.md or the spine should carry one sentence noting the deviation and why. Low risk
of causing implementation divergence (nobody will actually reach for Postgres here), but it's a
traceability defect worth fixing since a future reader diffing spine against SPEC will trip on it.

## 6. Tech stack currency / internal consistency

- **Room 2.6.x** stands out as stale relative to everything else in the table. Kotlin 2.4.0,
  Compose BOM 2026.04.01, AGP 9.2.0, and WorkManager 2.11.2 are all pitched as bleeding-edge/
  current-as-of-2026 versions, but Room 2.6.x was already the current line back in 2023–2024
  (Room progressed through 2.7 alphas adding KMP support well before this document's mid-2026
  date). Either this is a stale placeholder that should be bumped (likely candidate: Room 2.7.x or
  later) or there's an unstated reason (e.g. a KSP/KMP compatibility constraint) worth naming.
  Flag for verification before implementation starts.
- **"Retrofit + OkHttp 5.0"** conflates two libraries' version numbers into a single cell.
  OkHttp and Retrofit do not share a version scheme — Retrofit needed its own major bump to
  support OkHttp 5's API changes. Stating "5.0" as if it's one version for both is ambiguous and
  will send an implementer to the wrong artifact coordinates. Should read something like
  "Retrofit 3.x (OkHttp 5.0)" with Retrofit's actual version spelled out.
- **Compose BOM 2026.04.01** dated three months before this document's own creation date
  (2026-07-08/09) is slightly suspicious given the BOM's historical release cadence (roughly every
  4–6 weeks) — by July there would typically be one or two newer BOM releases available. Not
  necessarily wrong (cadence may have slowed), but worth a build-time check rather than treating it
  as settled.
- Kotlin 2.4.0 and AGP 9.2.0 are plausible extrapolations of each project's historical release
  cadence and don't show an internal inconsistency with each other or with WorkManager 2.11.2.

Net: the stack table is mostly plausible but not uniformly current — Room and the Retrofit/OkHttp
line need a second look before being treated as locked.

## 7. Capability → Architecture Map coverage

All eight capabilities (CAP-1 through CAP-8) appear in the map with a "Lives in" location and a
"Governed by" AD reference. No gaps. Good.

## 8. Structural/operational envelope

- **Deployment & environments**: explicitly covered (single target, no dev/stage/prod, manual APK,
  no CI/CD) — good, this is exactly the kind of thing that's easy to skip and wasn't here.
- **Infra/provider strategy**: implicitly fine — there is no infra to choose (direct calls to
  three externally-fixed endpoints), and the Structural Seed diagram makes the topology explicit.
- **Operations (monitoring/logging/alerting/backup)**: partially covered. Logging has one line in
  the Consistency Conventions table (a single tagged `Logger` wrapper) — adequate. But there is no
  mention anywhere of backup/data-recovery (see §1) or of how the operator/requester would notice
  if the app silently stops making progress for reasons other than the explicit compliance halt
  (e.g. persistent WorkManager failures). This isn't necessarily something that needs a new AD, but
  it's a structural dimension this altitude owns and currently neither decides nor defers — it's
  just absent.

## 9. Terseness

The spine is appropriately terse — each AD is Binds/Prevents/Rule in three lines, no rationale
essays, no restating of SPEC content. It reads as a build substrate, not a memlog. This checklist
item passes cleanly.

---

## Summary of findings by severity

| Severity | Finding |
| --- | --- |
| Critical | `COMPLIANCE_STATE` entity's `isCompliant`/`lastCheckedAt` shape reintroduces the caching design mechanics.md explicitly says "should not be built," and AD-10 doesn't clarify the entity is read-only/display-only — real risk of inverting the fail-open guarantee (CAP-7). |
| High | No Room schema migration strategy decided, despite AD-9 committing to reinstall-over-existing-install updates that persist the DB across schema changes. |
| Medium | Image file storage location/strategy (app-private vs. MediaStore/URI) undecided for CAP-1's upload path; interacts with AD-9 and the deferred keystore item. |
| Medium | SPEC.md states "local PostgreSQL," spine uses Room/SQLite throughout with no reconciling note — likely a SPEC typo, but an unaddressed contract mismatch. |
| Medium | Stack table: Room 2.6.x looks stale next to the rest of the 2026-current versions; "Retrofit + OkHttp 5.0" conflates two libraries under one version number. |
| Low | No backup/data-recovery story for the single-device datastore; not decided, deferred, or flagged as an open question. |
| Low | AD-3's "token reference" for the WhatsApp credential is ambiguous about at-rest storage (literal secret vs. indirection) — likely an accepted risk given the app's overall no-auth posture, but should be stated rather than implied. |
