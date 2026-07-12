---
type: adversarial-review
target: ARCHITECTURE-SPINE.md (image-distributor-app, architecture-RIS-2026-07-08)
reviewer: adversarial-lens agent
date: 2026-07-09
---

# Adversarial Review — image-distributor-app Architecture Spine

Method: for each area of the spine, construct two literal-minded implementation
sessions (with a context reset between them) that each satisfy every stated AD
and consistency convention, and check whether they could still produce
incompatible code. Every pair found is a hole in the spine.

## Finding 1 — Transmission row lifecycle is unspecified: one row per logical send vs. one row per attempt

**The gap:** The ER diagram gives `TRANSMISSION{id, receiverId, imageId, sentAt, status, attemptCount}`
with `status = PENDING|SENT|FAILED`, and CAP-4/mechanics.md require up to 3
retries per queued item. No AD or convention says whether a retry updates the
*same* row (id fixed, `attemptCount` incremented, `status` transitions
PENDING→FAILED→PENDING→SENT) or inserts a *new* row per attempt.

**Two divergent builds:**
- **Session A (queue/worker session):** treats one `Transmission` row as the
  unit of a queued send. `SendWorker` does `UPDATE ... SET attemptCount = attemptCount+1, status = ...`
  on retry. One logical send = one row, ever.
- **Session B (dashboard session, built independently):** assumes — reasonably,
  from "log of what was sent and when" (CAP-5) — that each attempt is its own
  auditable event, and its queries `GROUP BY receiverId, imageId, date(sentAt)`
  or count `status = SENT` rows per day to render the dashboard.

If Session A actually inserted a new row per retry attempt (equally licensed by
the schema and AD-6/AD-7, which only constrain id type and timestamp
representation, not row cardinality), Session B's dashboard double/triple-counts
failed-then-retried sends as separate deliveries — directly breaking CAP-5's
success criterion ("resolve a customer's 'I didn't get my images' dispute").
Conversely if Session B assumed row-per-attempt but Session A built
update-in-place, retention purge (CAP-8, "purge records older than N days")
purges based on `sentAt` of a row that has been silently overwritten across
retries spanning a day boundary, changing purge semantics from what CAP-8's
UI implies.

**AD that failed to prevent it:** AD-6/AD-7 govern id type and timestamp
representation but say nothing about row cardinality per logical send. AD-10
assigns "the offline-safe queue" to `worker/SendWorker` + `data/repository`
but does not say the Transmission row *is* the queue's state machine (vs. a
pure append-only log written only on terminal outcome).

**Suggested fix:** Add an AD (or extend AD-7/AD-10) stating explicitly: "One
`Transmission` row is created per queued send at selection time (status=PENDING,
attemptCount=0); retries mutate that same row in place (attemptCount++,
status transitions); a row only ever reaches one terminal state (SENT or
FAILED-after-3-attempts) and is never duplicated." This pins both the queue
session and the dashboard session to the same row semantics.

## Finding 2 — Who fetches data for `ImageSelectionEngine` and `ComplianceGate`? AD-1 vs AD-5/AD-10 point in different directions

**The gap:** AD-1's rule is literally "Dependencies only point downward per
the diagram above (`ui`, `worker` → `domain` → `data`)" — i.e., `domain` is
*allowed* (per the arrow) to depend on `data`. But AD-5's rule names only
"ViewModels and Workers" as Repository callers ("Repositories are the sole
callers of DAOs and remote clients" — silent on whether `domain/` classes may
also call a Repository). AD-10 describes `ImageSelectionEngine`/`ComplianceGate`
as where "these rules are implemented," with no statement of whether they are
pure functions or have injected data dependencies.

**Two divergent builds:**
- **Session A (CAP-3 domain-logic session):** builds `ImageSelectionEngine`
  as a pure function — `select(activeImages: List<Image>, recentTransmissions: List<Transmission>, min: Int, max: Int): List<Long>` —
  with zero dependencies, on the theory that AD-5 restricts *all* repository
  access to ViewModel/Worker call sites and domain must stay side-effect-free.
- **Session B (CAP-4 worker/queue session, built later with no memory of
  Session A's signature choice):** wires `SendWorker` on the assumption that
  `ImageSelectionEngine` is self-sufficient per AD-10 ("business rules live
  only in domain") and constructs it with a `Repository` injected, calling
  `engine.selectForReceiver(receiverId)` and expecting the engine to fetch
  images/transmissions itself — because AD-1's arrow literally licenses
  `domain → data`.

Both sessions can point to the letter of an AD to justify their choice; the
two `ImageSelectionEngine` constructor/call signatures are incompatible, and
whichever session runs second either can't compile against the other's engine
or silently reimplements the constructor, quietly duplicating (and risking
drift in) the very fetch logic the spine is trying to keep singular. The same
tension applies verbatim to `ComplianceGate` (does it take an already-fetched
`AppResult<ComplianceResponse>` from the Worker, or does it hold a repository
reference and make the call itself?).

**AD that failed to prevent it:** AD-1's rule text ("domain → data" is a valid
arrow) is in tension with AD-5's enumeration of only ViewModel/Worker as
Repository callers, and AD-10 doesn't resolve it either way.

**Suggested fix:** Amend AD-5 (or add a new AD) to state explicitly: "`domain/`
classes (`ImageSelectionEngine`, `ComplianceGate`, `RetentionPolicy`) are pure
— they receive all data as method parameters and hold no Repository/DAO/remote
references. Only `Worker`/`ViewModel` call sites fetch via Repository and pass
results in." (Or the inverse, if the intent was for domain to own fetching —
either is fine, but the spine must pick one, since AD-1's diagram currently
reads as license for the opposite of what AD-5's prose implies.)

## Finding 3 — Spine's own ER schema contradicts mechanics.md's explicit "no caching" rule for compliance

**The gap:** `mechanics.md` step 7 is unambiguous: *"No local caching of the
compliance result is needed... An earlier draft... cached the result for 2
days; that was superseded... and should not be built."* Yet the spine's own
ER diagram defines `COMPLIANCE_STATE{id, nickname, city, isCompliant,
lastCheckedAt}` — a persisted cache of exactly the kind mechanics.md forbids,
and the Capability Map assigns CAP-7 to `domain/ComplianceGate` +
`worker/ComplianceCheckWorker` without saying which one is authoritative for
a live send decision.

**Two divergent builds:**
- **Session A (compliance session):** takes the ER diagram literally —
  `ComplianceCheckWorker` runs on its own WorkManager schedule, calls the API,
  and **writes** `isCompliant`/`lastCheckedAt` into `COMPLIANCE_STATE`. Any
  other part of the app (including `SendWorker`) that needs a compliance
  answer **reads the stored row** — a real, if slow-to-update, cache.
- **Session B (queue session, or a second compliance-adjacent session run
  later):** takes mechanics.md literally — no cache is ever consulted as the
  gate for sending; instead `SendWorker` (or `ComplianceGate` invoked from
  it) calls the compliance API **fresh, synchronously, at/around each send**,
  using `COMPLIANCE_STATE` only to persist registration identity
  (nickname/city, locked per CAP-6) plus the *last known* value for the
  Settings/Compliance UI to display — never as the actual gate input.

These are functionally different systems: in Build A, a `false` compliance
result can take up to one `ComplianceCheckWorker` interval to actually stop
sending (`SendWorker` never checks live); in Build B, every send triggers its
own network round-trip and `ComplianceCheckWorker` becomes redundant/vestigial.
If Session A wrote the schema-driven cache and Session B wired `SendWorker`
per mechanics.md's "check fresh" framing without reading `COMPLIANCE_STATE`
at all, you get two compliance codepaths that can disagree at the same
moment — one says continue, the other (if consulted) says halt, and it is a
coin flip which one the retry-vs-send race actually touches.

**AD that failed to prevent it:** AD-10 assigns the "fail-open rule" to
`ComplianceGate` but doesn't say what `ComplianceGate`'s input is (see also
Finding 2) or that `COMPLIANCE_STATE` is a display-only mirror rather than
the live gate. AD-3/AD-2 govern *where the client lives*, not *when it's
called relative to a send*. Nothing in the spine flags or resolves the direct
tension with mechanics.md — this is the single most concrete instance of "the
architecture and its own source-of-truth disagree," and it's a schema-level
disagreement, not a stylistic one.

**Suggested fix:** Add explicit language (ideally amending AD-10 or adding a
new AD) resolving this: e.g. "`COMPLIANCE_STATE` is a **display mirror only**
— `SendWorker` never reads it to decide whether to send. The live gate is:
call the compliance API synchronously before each send batch (fail-open on
any error/timeout, per mechanics.md); persist the result to `COMPLIANCE_STATE`
purely so the UI can show 'last checked at X, status Y'." Or, if the intended
design really is a periodic background check with a stored flag, then say so
explicitly and update mechanics.md's "no caching" line to match — the two
canonical documents cannot both be followed literally as currently written.

## Finding 4 — Singleton-vs-history ambiguity for `RetentionSetting` (and `ComplianceState`) rows

**The gap:** `RETENTION_SETTING{id, retentionDays}` and `COMPLIANCE_STATE{id,
nickname, city, isCompliant, lastCheckedAt}` both look like "settings" tables
that should hold exactly one current row, but nothing declares them singleton
(no unique constraint, no fixed id=1 convention documented, AD-6 only says
"autoincrement Long," which by definition allows multiple rows to accumulate).

**Two divergent builds:**
- **Session A (Settings/Retention UI session):** builds retention editing as
  a `@Update` DAO call against a single bootstrapped row (assumes a seed
  row exists, e.g. from a Room `RoomDatabase.Callback.onCreate`).
- **Session B (RetentionPurgeWorker session, built without knowledge of
  whether a seed row was ever added):** defensively queries
  `SELECT * FROM retention_setting ORDER BY id DESC LIMIT 1`, falling back
  to a hardcoded default of 30 if no row exists at all, and its "current
  settings" read path never assumes row 0 was seeded.

If Session A's seed migration never actually shipped (a very plausible gap
across two separate builds with a context reset in between — nobody file
literally says "seed this table at DB creation"), the Settings screen's
`@Update` call is a silent no-op forever (Room `@Update` does nothing if the
target id doesn't match an existing row), and the operator's retention-days
change never takes effect even though the UI shows success — while
`RetentionPurgeWorker` keeps purging on the hardcoded 30-day fallback. The
same class of bug applies to `COMPLIANCE_STATE`'s single row (first-run
registration write vs. every subsequent read/update path).

**AD that failed to prevent it:** No AD states that `RetentionSetting` and
`ComplianceState` are singleton tables, who seeds the initial row, or what a
Repository does on a "row not found" read (return a domain default vs.
surface `AppResult.Failure`).

**Suggested fix:** Add a line to AD-6 or a new short AD: "`RetentionSetting`
and `ComplianceState` are singleton tables (exactly one row, fixed `id = 1L`,
upserted via `@Insert(onConflict = REPLACE)` — never plain `@Update`). Both
are seeded with defaults (`retentionDays = 30`, `isCompliant = true`) in the
Room `RoomDatabase.Callback.onCreate`, so a read path never has to handle a
missing row."

## Finding 5 — `Receiver.scheduleTime` is typed `string` with no format, and sits outside AD-7's timestamp rule

**The gap:** AD-7 mandates epoch-millis storage / `Instant` domain boundary
for "timestamps," but `RECEIVER.scheduleTime` is declared as a bare `string`
in the ER diagram — implicitly exempted from AD-7 because it's a recurring
daily time-of-day, not an absolute instant, but nothing in the spine says so
or gives its format.

**Two divergent builds:**
- **Session A (CAP-2 Receiver-config UI session):** stores `scheduleTime` as
  `"HH:mm"` local time (e.g. `"09:30"`), the natural choice for a time-picker
  Compose widget.
- **Session B (CAP-4 SendWorker scheduling session, built separately):**
  expects (and parses) minutes-since-midnight encoded as a numeric string
  (`"570"`), or a `"HH:mm:ss"` triple, to compute the delay until the next
  WorkManager trigger — an equally defensible choice for scheduling math, and
  nothing in the spine rules it out.

Neither violates any AD (AD-7 doesn't apply to non-timestamp strings; AD-6
doesn't apply to non-id fields); the two sessions simply pick different
string grammars for the same column, and `SendWorker`'s parser throws or
silently mis-schedules against data written by the UI session.

**AD that failed to prevent it:** AD-7 covers timestamps only; there is no
AD or convention entry for "recurring time-of-day" representation, and the
Consistency Conventions table's "Data & formats" row is silent on it.

**Suggested fix:** Add one line to the Consistency Conventions table: "Daily
recurring time-of-day (`Receiver.scheduleTime`) is stored as `"HH:mm"` 24-hour
local device time, parsed with `java.time.LocalTime.parse`." Small, cheap,
closes the gap completely.

## Finding 6 — WorkManager fan-out granularity is undecided: per-receiver periodic work vs. single polling worker

**The gap:** CAP-2 requires each of up to ~10 receivers to send "according to
its own schedule." AD-4 mandates "all scheduled/background work runs through
WorkManager Workers" but doesn't say whether that means one
`PeriodicWorkRequest` enqueued per receiver (unique work name keyed by
`receiverId`, next-run computed from that receiver's `scheduleTime`), or a
single low-frequency `SendWorker` (WorkManager's periodic minimum is 15
minutes) that wakes up regularly and internally scans all receivers for "is
it your time yet."

**Two divergent builds:**
- **Session A (Receiver-config session, wiring "save receiver" to
  scheduling):** on receiver create/update, enqueues
  `WorkManager.enqueueUniquePeriodicWork("send-$receiverId", ..., request)`
  where `SendWorker`'s `inputData` carries a single `receiverId` — one Worker
  instance = one receiver.
- **Session B (SendWorker implementation session, built independently from
  the queue/CAP-4 spec alone):** implements `SendWorker` as a single global
  periodic job with **no input data**, reading all receivers itself each run
  and checking each one's `scheduleTime` against "now" to decide who's due.

Both are "WorkManager only," satisfying AD-4 to the letter. But Session A's
enqueue call passes `receiverId` that Session B's `SendWorker.doWork()` never
reads (or crashes on missing key if it does `inputData.getLong("receiverId")`
non-nullably); Session B's scan-based worker, if unioned with Session A's
per-receiver enqueues, ends up firing every receiver's send logic multiple
times per scheduled window (once per unique per-receiver work request, once
more per global scan). This is a concrete, silent double-send or crash bug
that both sides can build to spec independently and only surface at
integration.

**AD that failed to prevent it:** AD-4's rule only constrains the mechanism
family (WorkManager, not AlarmManager/foreground service); it is silent on
fan-out shape (per-entity scheduled work vs. single poller), which is exactly
the kind of decision two Android developers reasonably split on.

**Suggested fix:** Add to AD-4 (or a new AD): "One `PeriodicWorkRequest`
(unique work name `\"send-$receiverId\"`) is enqueued per receiver, re-enqueued
whenever that receiver's schedule is edited; `SendWorker.inputData` always
carries exactly that receiver's `receiverId`. There is no global scanning
Worker for sends." This also resolves how `scheduleTime` edits propagate
(re-enqueue vs. rely on next scan), tightening Finding 5 at the same time.

## Deferred section — checked for premature deferral

Reviewed each deferred item against this project's actual scale (one device,
one operator, ~10 receivers, single developer building across sessions):

- **App signing/keystore** — genuinely inert w.r.t. code structure; no
  incompatibility risk. Correctly deferred.
- **Automated test framework specifics** — no cross-session data-shape risk;
  correctly deferred.
- **WhatsApp template content/approval** — external/Meta-side; doesn't affect
  in-app code shape until content exists. Correctly deferred, though note:
  the *code path* that calls WhatsApp Cloud API with a template name/params
  is already touched by AD-2/AD-3 (single client, centralized config), so
  when template IDs do land they have exactly one place to go — fine as is.
- **Analytics/crash reporting** — correctly deferred, no interaction with any
  other AD.
- **Multi-operator/multi-tenant support** — correctly deferred as a scope
  decision, but flagged as a *contributing cause* of Finding 4: because the
  spine assumes exactly one operator, it never had to say "singleton table"
  out loud, and that omission is what let two sessions diverge. This isn't a
  reason to un-defer multi-tenancy — it's a reason the singleton assumption
  needs to be made explicit somewhere (see Finding 4's fix), rather than
  left implicit under the non-goal.

No item in Deferred should be un-deferred; Finding 4's fix is the correct
place to absorb the one real risk this section indirectly created.

## Summary table

| # | Finding | AD(s) implicated | Severity |
|---|---|---|---|
| 1 | Transmission row-per-send vs. row-per-attempt | AD-6, AD-7, AD-10 (gap) | High — breaks CAP-5 dashboard accuracy |
| 2 | Domain classes pure vs. self-fetching (AD-1 vs AD-5) | AD-1, AD-5, AD-10 | High — incompatible constructor/call contracts |
| 3 | COMPLIANCE_STATE schema contradicts mechanics.md "no cache" rule | AD-10 (gap), source conflict | Critical — two disagreeing gate codepaths |
| 4 | Singleton vs. history rows for settings tables | AD-6 (gap) | Medium — silent no-op bug class |
| 5 | `scheduleTime` string format unspecified | AD-7 (scope gap) | Medium — parse failure / mis-scheduling |
| 6 | WorkManager fan-out granularity undecided | AD-4 (scope gap) | High — double-send / crash at integration |
