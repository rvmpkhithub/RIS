---
name: ImageDrop
description: Private Android utility app that automates a daily photo-subscription business. Built on Material 3 (Jetpack Compose default), no custom brand system — warm editorial palette (paper, ink, WhatsApp teal-green, gold) carried over from the project's brainstorm keepsake, applied within a restrained, professional structure.
status: final
updated: '2026-07-09'
colors:
  primary: '#128C7E'
  on-primary: '#FFFFFF'
  primary-dark: '#25D366'
  on-primary-dark: '#0E1B18'
  surface: '#FBF6EC'
  surface-dark: '#1C1712'
  on-surface: '#2B2620'
  on-surface-dark: '#F0E9D8'
  surface-variant: '#EFE6D2'
  surface-variant-dark: '#2B2620'
  outline: '#CBB98A'
  outline-dark: '#5C5346'
  error: '#C2452C'
  on-error: '#FFFFFF'
  success-muted: '#128C7E'
  gold-border: '#C9A13B'
typography:
  headline:
    note: 'Serif (Android generic "serif" family, ~ Georgia/Palatino) — screen titles only (e.g. "Receivers", "Dashboard"), Material 3 Headline Small size'
  title:
    note: 'Sans-serif (Roboto, platform default) — list item primary text, dialog titles, Material 3 Title Medium size'
  body:
    note: 'Sans-serif (Roboto) — form fields, primary reading text, Material 3 Body Large size'
  label:
    note: 'Sans-serif (Roboto) — chips, toggle labels, meta text (timestamps, counts), Material 3 Label Medium size'
rounded:
  sm: 8px
  md: 12px
  lg: 16px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
components:
  compliance-halt-screen:
    background: '{colors.surface-variant}'
    text-color: '{colors.on-surface}'
    accent: none
  image-grid-item:
    corner: '{rounded.sm}'
    inactive-treatment: 'desaturated + 60% opacity overlay, no red X'
  receiver-row:
    corner: '{rounded.md}'
    divider: '{colors.outline}'
  retention-row:
    corner: '{rounded.sm}'
  master-schedule-list:
    corner: '{rounded.sm}'
---

## Brand & Style

This is not a consumer product — it's a private operational tool one person runs on one tablet. It should read like a well-made internal business tool, not a marketing surface: no mascot, no onboarding flourish, no marketing voice. That restraint holds even after this update — what changed is the *palette*, not the posture.

The color and type pairing is carried over deliberately from this project's brainstorm keepsake (warm paper, dark ink, WhatsApp teal-green, a touch of gold) — reused here because the operator liked it and asked for continuity between the project's own artifacts and the shipped app, not because the app needed a brand refresh. The app's existing restraint principle (one functional accent color, text-only status, no decoration for its own sake) still governs: the keepsake's gold and its decorative flourishes (polaroids, animation, dashed washi-tape borders) stay in the keepsake — they don't belong in a tool meant to be glanced at, not admired.

Built on Material 3 defaults (Jetpack Compose ships this for free) rather than a custom design system — only the color scheme and type pairing are swapped from Material 3's own defaults; component shapes, elevation, and interaction patterns are untouched.

## Colors

- **Primary (`#128C7E` light / `#25D366` dark, WhatsApp teal-green)** — the only chromatic action color. Used for the primary action on each screen (Add Receiver, Upload Images, Save, Continue) and nothing else. The darker teal reads calmer against the warm paper background in light mode; the brighter green carries better contrast against the dark surface in dark mode.
- **Surface (`#FBF6EC` light / `#1C1712` dark, "paper")** — warm off-white instead of a clinical gray, matching the keepsake's paper tone. Dark mode uses a warm near-black brown, not a cool black.
- **On-surface (`#2B2620` light / `#F0E9D8` dark, "ink")** — warm dark brown text on light, warm cream text on dark. Never pure black or pure white.
- **Surface variant (`#EFE6D2` light / `#2B2620` dark)** — a step darker/warmer than surface; used for the compliance-halt screen background and any subtly-recessed panel.
- **Outline (`#CBB98A` light / `#5C5346` dark)** — a warm tan/khaki divider instead of a cool gray one, at the same low contrast as before. Never a heavy border.
- **Error (`#C2452C`)** — reserved strictly for destructive confirmations (removing a receiver, discarding unsaved changes). **Deliberately not used for the compliance-halt screen** — see Do's and Don'ts.
- **Success-muted (`#128C7E`)** — used once, quietly, for a "Sent" state in the dashboard list. Never a badge, never a checkmark icon with a colored fill — just a text tint, same teal as the primary action color.
- **Gold border (`#C9A13B`, from the keepsake's card/plaque borders) — revised.** Per operator feedback, the app was reading as too plain against the keepsake's richer paper-card feel. Gold is now used *structurally*, as a card border/frame color, echoing the keepsake's `.meta-card`/`.core-insight`/`.plaque` treatment — never as a second interactive accent color. The "one accent for actions" rule still holds for anything tappable; gold decorates containers, it doesn't act.

## Typography

Headline (screen titles — "Setup," "Images," "Receivers," "Dashboard") uses Android's generic `serif` font family (resolves to Noto Serif on most devices), echoing the keepsake's Georgia/Palatino headlines. Everything else — form fields, list rows, labels, buttons — stays Roboto (Material 3's sans-serif default), matching the keepsake's Segoe UI body text. No custom font files are bundled; both are system-resolved generic families. No display-size text anywhere; this app never needs a hero moment.

## Layout & Spacing

Material's 4dp grid: `{spacing.1}`–`{spacing.6}`. Standard Compose screen margins (16dp). Single-column throughout — this is a phone/tablet utility app, not a dashboard product; no multi-pane layouts even on the tablet form factor, since the operator interacts with it briefly and infrequently (configure once, glance at the dashboard occasionally).

## Elevation & Depth

**Revised** — form content and placeholder panels now sit on a bordered "paper card" (gold `{colors.gold-border}` outline, soft shadow) echoing the keepsake's card treatment, rather than floating on bare background. The compliance-halt screen is the one deliberate exception — it stays a plain, borderless full-bleed panel, since that flat calm is the point (see Do's and Don'ts). The main background itself carries a very subtle warm radial gradient (paper → paper-dark, low contrast) instead of a flat fill, echoing the keepsake's hero gradient without competing with content.

## Shapes

Material 3 default shape scale: `{rounded.sm}` for list rows and form fields, `{rounded.md}` for cards (receiver cards, dashboard entries), `{rounded.lg}` for dialogs and sheets. Nothing fully rounded except icon buttons (`{rounded.full}`).

## Components

- **Compliance halt screen** — full-screen, `{colors.surface-variant}` background, no red, no warning-triangle iconography. Centered message text ("Not compliant — contact admin") in `{typography.title}`, a single line of `{typography.body}` explanatory text below it, nothing else on screen. No retry button, no dismiss action — see EXPERIENCE.md for why.
- **Image grid item** (Image Library screen) — square thumbnail, `{rounded.sm}` corners, active/inactive toggle as a simple switch overlaid bottom-right. Inactive images are desaturated with a 60% opacity overlay — not hidden, not marked with a red icon, just visually "dimmed out."
- **Receiver row** (Receivers screen) — `{rounded.md}` card: name + channel icon (WhatsApp/email) on top line, schedule summary (e.g. "4×/day", or "Uses master schedule" if the receiver has none of its own) + count range as `{typography.label}` meta text below — the full list (if any) is visible/editable on the Receiver Edit screen. Tap opens edit; trailing icon-button for delete.
- **Retention row** (Settings) — simple label-left/value-right row (current retention days), tap opens a numeric picker. No slider — a slider implies more granularity than this setting needs.
- **Master schedule list** (Settings) — reuses the exact "Schedule time list" component from Receiver Edit (EXPERIENCE.md#Component Patterns) verbatim: same add/remove rows, same minimum-4 inline error, same time picker. Sits below the retention row on the same screen, its own labeled section ("Master schedule").
- **Dashboard entry row** — timestamp in `{typography.label}`, image thumbnail (small, 40dp) leading, "Sent" in `{colors.success-muted}` trailing.

## Do's and Don'ts

| Do | Don't |
|---|---|
| Keep the compliance-halt screen neutral and calm (`{colors.surface-variant}`, no red) | Style the compliance halt like an error state — it's an external gate, not something the operator broke |
| One accent color (`{colors.primary}`), used only for primary actions. Gold (`{colors.gold-border}`) may frame containers/cards, never a tappable element | Color-code receivers, images, or channels by category, or use gold as a second *interactive* accent |
| Text-only status ("Sent", "Pending") | Icon badges or colored pills for status |
| Stock Material 3 components everywhere possible | Custom-drawn controls, custom navigation patterns |
| Single-column layout even on tablet | Multi-pane / master-detail layouts — this app is glanced at, not lived in |
| Serif for screen titles only, sans-serif everywhere else | Serif body text, decorative/script fonts, or the keepsake's animations and paper-texture flourishes |
