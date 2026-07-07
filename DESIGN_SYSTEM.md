# DuoShield Design System: "Sanctuary"

## Philosophy

DuoShield is not just a messaging app—it's a refuge for people in genuinely dangerous situations. The design must whisper "I understand what you're protecting," not shout it. Every element serves the user's sense of safety and trust.

## Visual Language

### Color Palette: Warm Darkness

The color system uses **warm, protective tones** rather than cold tech-darkness. This creates a sense of sanctuary—a warm room where someone can be themselves.

- **Deep Night** (`#0A0A0F`) — Primary background, protective and grounding
- **Shadow Surface** (`#1C1C24`) — Secondary surfaces, layered depth
- **Sanctuary Teal** (`#00C8C8`) — Accent color, represents safety and trust
- **Ghost White** (`#E0E0E0`) — Primary text, readable and warm
- **Whisper Grey** (`#A0A0A0`) — Secondary text, subtle and respectful

### Typography: Hierarchy Through Breathing

- **Headlines** — Generous line-height (1.3x), medium weight, warm spacing
- **Body text** — Readable size (16sp minimum), 1.5x line-height for comfort
- **Captions** — Subtle, never cramped, always readable
- **No all-caps** — Except for section headers (sparingly)

### Spacing: Organic, Not Rigid

- **Generous padding** — 16dp minimum for comfortable breathing
- **Asymmetric spacing** — Feels more human than perfect grids
- **Layered surfaces** — Each layer has clear visual hierarchy through shadow and color

### Shapes: Organic Curves

- **Corner radius** — 20dp for cards, 16dp for inputs, 12dp for buttons
- **No sharp corners** — Everything feels intentional and soft
- **Circular elements** — Avatars and badges use 50% radius

### Shadows: Subtle Depth

- **Elevation 1** — `0dp 2dp 8dp rgba(0,0,0,0.12)` — Subtle lift
- **Elevation 2** — `0dp 4dp 12dp rgba(0,0,0,0.16)` — Medium depth
- **Elevation 3** — `0dp 8dp 16dp rgba(0,0,0,0.20)` — Strong presence
- **No harsh shadows** — All shadows are soft and diffuse

## Component Design

### Chat Bubbles

**Incoming** — Surface color with subtle border, left-aligned
**Outgoing** — Teal-tinted dark with gradient, right-aligned
**Both** — Organic rounded corners (20dp), generous padding (12dp horizontal, 10dp vertical)

### Conversation List Items

- **Card-based** — Not rows, but elevated cards with breathing room
- **Avatar** — Large (56dp), circular, with online indicator
- **Typography** — Name (16sp bold), message preview (14sp secondary), timestamp (12sp tertiary)
- **Unread badge** — Subtle teal circle, not aggressive

### Input Fields

- **Background** — Surface color with 1.5dp border
- **Focus state** — Border becomes Sanctuary Teal, subtle glow
- **Placeholder** — Whisper Grey, respectful
- **No jarring focus animations** — Smooth 200ms transition

### Buttons

- **Primary** — Sanctuary Teal background, white text, 20dp radius
- **Secondary** — Surface background with border, text color primary
- **Tertiary** — Transparent with text color, no background
- **All buttons** — Generous padding (16dp horizontal, 12dp vertical)

### Headers

- **Background** — Deep Night (same as body, not a separate bar)
- **Subtle border** — 0.5dp divider at bottom for separation
- **Elevation** — Minimal, feels integrated not floating
- **Logo + title** — Generous spacing, breathing room

## Interaction Design

### Transitions

- **Screen transitions** — 300ms smooth fade + subtle slide
- **Button press** — 80ms scale (0.98), haptic feedback
- **Message send** — 200ms slide-up with fade-in
- **All animations** — Easing: `@android:interpolator/fast_out_slow_in`

### Micro-interactions

- **Message delivery** — Subtle checkmark animation (not jarring)
- **Online status** — Gentle pulse or glow (not flashing)
- **Typing indicator** — Soft animation, never distracting
- **Unread badge** — Appears smoothly, not suddenly

### Haptic Feedback

- **Button press** — Light haptic (ImpactFeedbackStyle.Light)
- **Message send** — Medium haptic (ImpactFeedbackStyle.Medium)
- **Error state** — Notification haptic (NotificationFeedbackType.Error)

## Principles

1. **Respect the user's context** — This app may be used in dangerous situations. Design for safety, not distraction.
2. **Minimize cognitive load** — Every element should be immediately understandable.
3. **Protect privacy visually** — No bright notifications, no attention-seeking elements.
4. **Organic over mechanical** — Curves, breathing space, and natural hierarchy over rigid grids.
5. **Sophisticated restraint** — Premium means knowing what NOT to do.
6. **Warmth over coldness** — Technology that feels human and protective.

## Implementation Notes

- All corner radiuses should use `<corners>` in shape drawables, not hard-coded values
- Shadows should use elevation attributes, not manual shadow drawables
- Colors should reference the color palette, never hard-coded hex values
- Typography should use consistent text appearance styles
- Animations should use Material motion easing curves
