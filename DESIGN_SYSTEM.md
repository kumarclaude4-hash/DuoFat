# DuoShield Design System: Classic

## Philosophy

DuoShield is a secure messaging app for people who need genuine privacy. The design prioritises clarity, restraint, and trust — every element serves the user's sense of safety without distraction.

## Visual Language

### Color Palette: Lavender / Classic

The color system uses a **deep lavender-dark** palette. Surfaces are near-black with a subtle blue-violet tint; the accent is a vibrant lavender that signals interactivity without shouting.

- **Classic Background** — Deep blue-dark, primary screen background
- **Classic Surface** — Slightly elevated surface for cards and inputs
- **Accent (Lavender)** (`ds_accent`) — Primary interactive color: buttons, badges, links
- **Primary Text** — Near-white, high contrast
- **Secondary Text** — Muted lavender-grey for meta information
- **Tertiary Text / Hint** — Very subtle, for placeholder and captions

### Typography: Hierarchy Through Breathing

- **Headlines** — Generous line-height (1.3×), medium weight
- **Body text** — Readable size (16sp minimum), 1.5× line-height for comfort
- **Captions** — Subtle, never cramped, always readable
- **No all-caps** — Except for section labels (sparingly)

### Spacing: Functional, Not Rigid

- **Generous padding** — 16dp minimum for comfortable breathing room
- **Consistent spacing tokens** — `ds_spacing_xs` through `ds_spacing_xxl`
- **Layered surfaces** — Visual hierarchy through color difference, not heavy shadows

### Shapes: Classic (Tight Functional Corners)

- **Corner radius** — 8dp for cards, 6dp for inputs, 4dp for buttons
- **Circular elements** — Avatars and badges use 50% radius

## Component Design

### Chat Bubbles

**Incoming** — `bg_bubble_theirs_classic`: surface-colored, subtle border, left-aligned  
**Outgoing** — `bg_bubble_mine_classic`: accent-tinted dark, right-aligned  
**Both** — Classic rounded corners, 13dp horizontal / 9dp vertical padding

### Conversation List Items

- **Row-based** — Flat rows with clear typographic hierarchy
- **Avatar** — 48dp circular, initials fallback with lavender-palette tint
- **Typography** — Name (bold), message preview (secondary color), timestamp (tertiary)
- **Unread badge** — Accent-colored pill, only shown when not muted

### Input Fields

- **Style** — `Widget.DuoShield.TextInput.Classic` (OutlinedBox, 6dp corners)
- **Background** — `classic_surface`
- **Focus state** — Border transitions to accent color

### Buttons

- **Primary** — Accent background, white text, `ds_corner_radius_card` radius
- **Outlined** — Accent border and text, transparent background
- **All buttons** — Generous touch targets (minimum 40dp height)

## Interaction Design

### Transitions

- **Screen transitions** — 300ms slide (slide_in_right / slide_out_left)
- **All animations** — Easing: `@android:interpolator/fast_out_slow_in`

### Micro-interactions

- **Message delivery** — Subtle checkmark ticks
- **Online status** — Dot indicator (no pulsing)
- **Typing indicator** — Soft text animation

### Haptic Feedback

- **Button press** — Light haptic
- **Message send** — Medium haptic
- **Error state** — Notification haptic

## Principles

1. **Respect the user's context** — This app may be used in sensitive situations. Design for safety, not distraction.
2. **Minimize cognitive load** — Every element should be immediately understandable.
3. **Protect privacy visually** — No bright notifications, no attention-seeking elements.
4. **Functional over decorative** — Tight corners, flat surfaces, clear hierarchy.
5. **Sophisticated restraint** — Knowing what NOT to add is as important as what to include.

## Implementation Notes

- All corner radii should use `<corners>` in shape drawables via `@dimen` tokens, not hard-coded values
- Colors should reference the color palette, never hard-coded hex values
- Typography should use consistent `TextAppearance.DuoShield.*` styles
- Animations should use Material motion easing curves
