---
name: ImageDrop
status: final
sources:
  - ../../../specs/spec-image-distributor-app/SPEC.md
  - ../../../specs/spec-image-distributor-app/mechanics.md
  - ../architecture/architecture-RIS-2026-07-08/ARCHITECTURE-SPINE.md
  - ../epics.md
updated: '2026-07-09'
---

# ImageDrop — Experience Spine

> Backfilled after SPEC.md, ARCHITECTURE-SPINE.md, and epics.md already existed — most behavioral decisions below trace directly to those, not invented here. Paired with `DESIGN.md`. Single-surface Android (phone or tablet), Material 3 (Jetpack Compose default). Single-operator posture: one person (the friend/operator) uses this app, briefly and infrequently, to configure and occasionally check on an otherwise-automated business.

## Foundation

Android, Jetpack Compose, Material 3 defaults (no custom UI system). `DESIGN.md` is the visual identity reference; this spine is the experience. No dark-mode preference was stated — **[ASSUMPTION]** follows system light/dark like any standard Android app, no in-app override needed given the low usage frequency.

## Information Architecture

| Surface | Reached from | Purpose |
|---|---|---|
| Setup (first-run) | Cold launch, before registration exists | Collect name/city once; trigger registration + first compliance check |
| Compliance Halt | Setup, or any subsequent launch/background check that returns explicit non-compliant | Dead-end: app is not usable, contact admin |
| Image Library | Bottom nav | Upload images; toggle active/inactive |
| Receivers | Bottom nav | List, add, edit, remove receivers |
| Receiver Edit | Receivers row tap, or "+" | One receiver's fields: name, channel, contact, count range, schedule times (one or more, minimum 4) |
| Dashboard | Bottom nav | Per-receiver timestamped send history, 30+ days |
| Settings | Bottom nav | **[ASSUMPTION — IA gap closed]** Retention-period control (Story 3.2 needed a home; none was named in the architecture's source tree, so a lightweight Settings surface is added here) |

Bottom `NavigationBar` (Images / Receivers / Dashboard / Settings) is the only top-level nav — four items, no drawer, no tabs-within-tabs. Setup and Compliance Halt sit outside this nav entirely; they're gates, not destinations.

→ Composition reference: none yet — this run skipped key-screen mocks (Fast path). See "Mock coverage" note at Finalize.

## Voice and Tone

Short, plain, no exclamation marks, no encouragement copy — this is a business tool, not a companion app.

| Do | Don't |
|---|---|
| "Not compliant. Contact admin." | "Oops! Something went wrong with your account." |
| "No images yet — upload some to get started." | "Your gallery is empty! Add some photos!" |
| "Sent" | "✓ Delivered successfully!" |
| "Enter a valid phone number." | "Invalid input!!" |

## Component Patterns

Behavioral; visual specs live in `DESIGN.md.Components`.

| Component | Use | Behavioral rules |
|---|---|---|
| Image grid item | Image Library | Tap toggles active/inactive directly on the grid (no separate edit screen) — matches Story 1.2's "toggle to inactive" being the entire interaction. |
| Receiver row | Receivers list | Tap → Receiver Edit. Swipe-to-delete (native pattern) with a confirm dialog — deletion is destructive and not undoable, per no persistence rule for removed receivers. |
| Receiver Edit form | Receivers | Channel choice (WhatsApp/email) is a segmented control at the top; it changes which contact field shows below (phone vs. email) — never show both fields at once. |
| Schedule time list | Receiver Edit | A receiver has one or more daily send times (minimum 4). Each time is its own row with an add/remove control; "Add time" opens the same time picker used elsewhere. Save is blocked with an inline error below the list ("Add at least 4 schedule times.") until the minimum is met — same inline-error-under-the-field pattern as the rest of this form, not a dialog/toast. |
| Compliance Halt screen | Gate | No interactive elements at all beyond system back/home. No retry button — per CAP-7/mechanics.md, only an external admin action (not a user action) can resolve this. A retry button would falsely suggest the operator can fix it himself. |
| Dashboard receiver picker | Dashboard | Simple dropdown/segmented selector at the top of the screen; switching it reloads the list below without leaving the screen. |
| Retention row | Settings | Tap opens a numeric picker (days), not a slider — the value only ever needs coarse adjustment (e.g. 30 → 60), never fine-grained. |

## State Patterns

| State | Surface | Treatment |
|---|---|---|
| First launch, no registration yet | Setup | Name/city form, single "Continue" action |
| Compliance check pending | Setup (post-submit) | Brief loading state, then routes to main app or Compliance Halt |
| Compliance API unreachable | (invisible) | Per NFR6/AD-11 fail-open: no banner, no error shown anywhere — the app behaves exactly as if it were compliant. This is intentional, not a missed error case. |
| Empty image library | Image Library | "No images yet — upload some to get started." |
| Empty receiver list | Receivers | "No receivers yet — add one to start sending." |
| Empty dashboard (new receiver, nothing sent yet) | Dashboard | "Nothing sent to this receiver yet." |
| Background send failure (retries exhausted) | (invisible) | Per the fail-open/graceful-degradation architecture, delivery failures are not surfaced as user-facing errors — the dashboard simply won't show a "Sent" entry for that slot. **[ASSUMPTION — flag for review]:** no story currently calls for a visible "failed" indicator; if the operator needs to know a send silently failed, that's a gap worth raising against epics.md, not something this UX pass should invent unasked. |
| Receiver form validation | Receiver Edit | Inline error text under the field ("Enter a valid phone number."), not a dialog/toast |

## Interaction Primitives

- Tap to act; no custom gestures beyond native swipe-to-delete on receiver rows.
- Long-press reserved for system text selection only.
- No pull-to-refresh anywhere — nothing on any screen is manually re-fetched; all data is local (Room) and updates live.
- **Banned:** confirmation toasts for routine saves (saving a receiver just returns to the list — the list itself is the confirmation), any animation on the Compliance Halt screen, badge counts on the bottom nav.

## Accessibility Floor

Behavioral; visual contrast lives in `DESIGN.md`.

- TalkBack: every interactive element labeled with role + state; the Compliance Halt screen's message is announced in full automatically on screen entry (this is the one screen where a user with no other context must understand what's happening from audio alone).
- Dynamic type honored through `DESIGN.md` typography tokens; no truncated labels at largest system text size — receiver rows and dashboard rows must wrap, not clip.
- Tap targets ≥ 48dp (Android standard), including the image-grid active/inactive toggle.
- Focus traversal follows visual reading order on every screen; the Receiver Edit form's channel-based field-swap does not break focus order when the visible field changes.

## Key Flows

### Flow 1 — First-time setup (Arjun, first evening with the tablet)

1. Arjun's friend hands him the tablet with the app already installed via WhatsApp.
2. He opens it; Setup asks for his name and city.
3. He enters them and taps Continue — this is a one-time, permanent choice (no edit path exists after this point, per CAP-6).
4. The app registers the install and checks compliance live.
5. Compliant → he lands on Image Library, empty.
6. He uploads his first batch of images, then switches to Receivers and adds his first customer (name, WhatsApp number, a 3-6 daily image range, a 9am schedule).
7. **Climax:** he adds his last regular customer, backs out to Dashboard — empty, nothing sent yet — and closes the app. Tomorrow morning, it runs itself for the first time without him touching it again.

Failure path: if step 4 returns explicit non-compliant, Arjun never reaches Image Library at all — he sees Compliance Halt immediately and has no path forward except contacting the admin (outside the app).

### Flow 2 — Resolving a customer dispute (Arjun, a week later)

1. A customer messages Arjun on WhatsApp: "I didn't get my photos today."
2. Arjun opens the app, taps Dashboard.
3. He picks that customer from the receiver selector.
4. He scrolls to today's date in the timestamped list.
5. **Climax:** he sees the exact time three images were sent that morning — he screenshots it and replies to the customer with proof, resolving the dispute in under a minute instead of guessing or re-sending blind.

Empty case: if genuinely nothing was sent (a real miss), the list simply shows no entry for that day — Arjun sees the gap himself and knows to investigate or resend manually, consistent with the architecture's "no backfill" rule putting that judgment call on him, not the app.

### Flow 3 — The compliance halt (Arjun, some months later)

1. Arjun opens the tablet one morning out of habit.
2. Unknown to him, the requester (RIS) flipped his install to non-compliant overnight for an unrelated reason.
3. The app's live compliance check (or the last cached launch check) returns non-compliant.
4. **Climax:** Arjun sees only "Not compliant. Contact admin." — calm, unstyled as an error, no retry button. He doesn't panic or assume he broke something; he picks up the phone and calls the person who set the app up for him, exactly as the message tells him to.

This flow only works if the halt screen reads as "a gate closed," not "you did something wrong" — which is why `DESIGN.md` explicitly bans error-red styling here.
