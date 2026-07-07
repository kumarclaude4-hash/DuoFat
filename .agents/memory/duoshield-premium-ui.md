---
name: DuoShield premium UI overhaul
description: Full visual redesign — color palette, drawables, 9 layout screens, TypingDotsView. All Java IDs preserved.
---

## Rule
Never change view IDs when updating layouts — Java code uses `findViewById` against all existing IDs. Only visual/layout properties may change.

## Design Tokens (June 2026 refresh)
- Background: `#04080F`, Surface: `#080E18`, Surface2: `#0D1825`
- Accent: `#00C8E8` (electric cyan), gradient start `#00E5FF` → end `#0077A3`
- Text primary: `#EDF3F7`, secondary: `#6E8FA0`, tertiary: `#3D5665`
- Bubble mine gradient: `#005577` → `#00283D` (20dp / 5dp corners)
- Bubble theirs gradient: `#111F30` → `#0A1520` with 1dp stroke `#1A2D40`
- Danger: `#E8485A`, Online: `#00E676`

## Screens updated
- `activity_sign_in.xml` — ConstraintLayout hero, Session-style green CTAs
- `activity_splash.xml` — wordmark + tagline with ambient glow
- `activity_conversation_list.xml` — 64dp toolbar, inline search bar, premium empty state
- `activity_chat_media.xml` — 66dp header, avatar+online dot, input pill bar
- `activity_lock_screen.xml` — ConstraintLayout, pill PIN input, glow behind shield
- `activity_settings.xml` — card-based rows, full-width profile header
- `activity_pairing.xml` — OR divider, pill ID card, premium tab buttons
- `item_conversation.xml` — 56dp avatars with E2E badge overlay, hairline divider
- `item_message.xml` — 20dp bubble corners, refined meta row

## Drawables updated (16 files)
bg_bubble_mine/theirs, bg_button_gradient, bg_avatar_circle, bg_input_field_whatsapp,
bg_send_button_whatsapp, bg_shield_glow, bg_online_dot, bg_fab, bg_settings_card,
bg_input_field, bg_badge, bg_profile_avatar, bg_id_card, bg_hero_gradient,
bg_reply_preview, bg_date_header, bg_empty_state, bg_bubble_welcome_green

## TypingDotsView
- `com.duoshield.app.ui.TypingDotsView` — custom View, 3 cyan dots bouncing staggered 130ms each
- Placed in `bg_bubble_theirs`-styled pill inside `typingIndicatorRow`
- Original `@+id/typingIndicator` TextView kept as 0×0 gone view (Java holds the ref)
- `startDotAnimation()` / `stopDotAnimation()` public API; auto-start in `onAttachedToWindow()`

**Why:** Java member `typingIndicator` is declared but never `setText`'d, so it can be invisible. The row's show/hide alpha animation is on `typingIndicatorRow` (LinearLayout) which is preserved.

## Release APK build (June 2026)
- Built via `assembleRelease` workflow in 2m 52s
- Credentials: GOOGLE_SERVICES_JSON + GOOGLE_APPLICATION_CREDENTIALS_JSON injected via Python at build time
- Signed with `app/duoshield-release.keystore` (KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD)
- Output: `app/build/outputs/apk/release/app-release.apk` (107 MB)
- Zipped: `app/build/outputs/apk/release/duoshield-release.zip` (44 MB) via `python3 zipfile`
- `zip` CLI not available in Replit — always use `python3 -c "import zipfile..."` to zip

**How to apply:** `adb install app-release.apk`
