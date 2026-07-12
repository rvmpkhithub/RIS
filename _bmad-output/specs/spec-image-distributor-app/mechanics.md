# Mechanics

Supporting detail for CAP-3, CAP-4, CAP-6, CAP-7. This is the HOW behind those capabilities' WHAT.

## Selection algorithm (CAP-3)

For each receiver, at each scheduled send:

1. Pick a random count Z between that receiver's configured min X and max Y (inclusive). Z is re-rolled independently at every send — it is not fixed across days.
2. Build the eligible pool: all currently-active images for that receiver, excluding any image sent to that same receiver within the last 7 days (checked against the transmission ledger, not a separate dedupe table).
3. If the eligible pool has at least Z images, pick Z at random from it — no duplicates within the batch.
4. If the eligible pool has fewer than Z images (the 7-day exclusion made the math impossible), fall back to allowing repeats: pick from all active images regardless of the 7-day rule until Z is reached.
5. If the total active image count is itself less than Z (independent of the repeat rule), send only however many active images exist. Do not duplicate or "top up" to reach Z.

## Compliance flow (CAP-6, CAP-7)

1. First launch: collect first name/nickname + city. Lock both permanently — no edit path exists after this point. Neither field carries authentication; the API calls in this flow are unauthenticated by design.
2. POST {first name/nickname, city} to the admin's registration endpoint. The install is registered as compliant by default.
3. Admin flips an install to non-compliant separately, offline, outside this app — there is no expiry date on a compliance value; it only changes when explicitly set.
4. Whenever the device is online, call the hardcoded compliance API with {first name/nickname, city} for a fresh answer before/around scheduled sends.
5. Only an explicit `false`/non-compliant response halts the app: show a static "contact admin" screen, stop all sending, change nothing else.
6. If the API call itself fails (unreachable, timeout, error) — this is not a non-compliant answer by itself. **[Amended during Story 1.1 implementation, 2026-07-09]** Check the last *confirmed* live result: if it was explicitly non-compliant, the halt persists (an unreachable check does not un-halt a confirmed halt). If the last confirmed result was compliant (or none exists yet), fail open as before — do not halt, retry at the next scheduled check, sending/access continues normally in the meantime. This closes a real bypass: without it, an explicitly halted install could relaunch offline and walk straight past the halt.
7. A minimal local cache of the last confirmed live result (compliant/non-compliant + timestamp) is needed for rule 6 above — asymmetric use only: it can extend a confirmed halt, it must never be read to fabricate a "compliant" answer a live check didn't itself provide. (An earlier draft of this design cached the result for 2 days *as a substitute for* live-checking; that remains rejected — this cache is only consulted when a live check fails, never in place of one.)

## Delivery channels

- **WhatsApp:** via the WhatsApp Business API (Cloud API). Requires a provisioned WhatsApp Business account (in progress as of this writing) and, per Meta's platform rules, approved message templates for outbound sends outside a customer-initiated conversation window.
- **Email:** via Google/Gmail SMTP.
- No other delivery channels are in scope.

## Retention and purge (CAP-8)

- Transmission history is purged once it exceeds a retention window.
- Default window: 30 days, matching the dashboard's display range (CAP-5).
- The window is operator-configurable; changing it changes what gets purged on the next purge cycle, not retroactively re-purging already-deleted rows.

## Queue and delivery (CAP-4)

- The queue holds `{receiver_id, image_id, scheduled_for}` — never image bytes.
- On send failure, retry the same queued item up to 3 times. No distinction is made between "bad receiver number" and "transient network failure" — both get the same 3 retries.
- If the tablet reconnects after an outage, only the current day's scheduled batch is resumed from the queue. Batches from earlier missed days are not backfilled — the operator is expected to have covered those manually.
- An image already selected and queued is still sent even if it is flagged inactive before the queue actually fires. "Inactive" only removes an image from future selection (step 2-3 of the selection algorithm above), it does not cancel an already-queued send.

## Failure-mode table

| Scenario | Behavior |
|---|---|
| Compliance API returns explicit non-compliant | Halt all sending immediately; show "contact admin" message |
| Compliance API unreachable / times out | Do not halt; retry at next scheduled check; keep sending in the meantime |
| Tablet offline through an entire scheduled send window | On reconnect, resume only that day's batch; do not backfill prior missed days |
| Active image pool smaller than the chosen count Z | Send only the available active images; no duplicates, no topping up |
| 7-day no-repeat pool exhausted (not enough unsent-in-7-days images to reach Z) | Allow repeats as a fallback, still capped at the active image count |
| Image flagged inactive after being selected/queued but before the queue fires | Still sent as queued; inactive only blocks future selection |
| Transmission to a receiver fails (any reason) | Retry up to 3 times; receiver's number correctness is the operator's own responsibility |
