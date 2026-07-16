---
name: DuoShield UI accent color
description: Correct brand palette — lavender/violet, NOT cyan. Corrects stale memory from an earlier overhaul attempt.
---

## Rule
The DuoShield brand palette is **lavender/violet**, not cyan.

- `ds_accent` = `#9A81FF` (Brand Lavender — primary accent, buttons, FAB, ticks, badges)
- `ds_accent_dim` = `#7C6BFF`
- `ds_accent_deep` = `#6654E8`
- Background = `#191620` (`ds_background`)
- Surface = `#24202E` (`ds_surface`)
- Divider = `#3A3548` (`ds_divider`)
- Bubble mine = `#2A2045` (`ds_bubble_mine`)
- Bubble theirs = `#24202E`
- Text primary = `#F4F1FA`
- Online = `#6BBF8A`, Danger = `#D96A7C`, Warning = `#E7B15D`

**Why:** An earlier session recorded the accent as `#00C9E0` (cyan) after a "premium UI overhaul". That overhaul was later reversed/replaced with the current lavender redesign in `colors.xml`. The cyan value is gone; `#9A81FF` is the authoritative accent.

**How to apply:** Any hardcoded hex in Java/Kotlin that is a UI accent colour must use `#9A81FF` (or the `ds_accent` resource), not any cyan/teal value. Waveform played colour = `#9A81FF`, unplayed = `#3A3548`.
