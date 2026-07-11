---
name: DuoShield UX audit implementation
description: Status of the DuoShield_UX_Audit_1783745017361.md 16-item pass — what's done, what's deferred, and naming to keep consistent going forward.
---

All 5 critical items and all 7 high-impact items from the UX audit were already implemented or completed in this pass (confirmation dialogs, reworked duress-PIN-as-"additional code" flow, dark mode toggle removed entirely rather than half-implemented, consistent "Account ID"/"Recovery Phrase" terminology, debug-only storage diagnostics gate, PinDotsView + auto-submit lock screen, per-chat key fingerprint entry, toolbar/button naming, 12sp caption floor, 48dp tap targets, profile photo contentDescription).

**Settings is now split**: SettingsActivity is a slim home screen (profile header + 4 nav rows) that routes to SecurityPrivacySettingsActivity, AppearanceNotificationsSettingsActivity, BackupStorageSettingsActivity, DangerZoneSettingsActivity. Any future settings-related work should add a new row/activity rather than growing one of these back into a monolith.

**Sanctuary/Premium UI mode is fully removed** — no more `isSanctuary()`, `sanctuaryMode` params, or `_premium`/`_sanctuary` resources. `bg_conversation_item_sanctuary.xml` survived as `bg_id_card_outline.xml` (used by the Add Contact ID card) — don't recreate a sanctuary-named drawable.

**Motion/haptics polish added**: `ButtonPressAnimator.attach(view)` (80ms scale-to-0.98 + light haptic) applied to lock screen unlock, chat send button, sign-in CTAs. `HapticHelper.send()` now actually fires on message send. Outgoing bubble insert animation fixed to 200ms and fires once per insert via `pendingAnimMsgId` in MessageAdapter (not on every rebind).

**Deferred as follow-up tasks** (proposed, not done): first-run onboarding walkthrough (item 14 — needs content/design decisions), skeleton/shimmer loading states for conversation list and chat thread (item 15 remainder — call history empty-state parity was fixed, but no shimmer loading exists anywhere in the app).
