---
name: DuoShield AddContactActivity patterns
description: AddContactActivity gallery QR, share link, clipboard paste, and deep link conventions
---

# AddContactActivity key conventions

## Deep link scheme
`duoshield://add/{userId}` — host=`add`, path=`/{userId}`.
AddContactActivity is `exported="true"` with an intent-filter on this scheme.
Intent is handled in `handleDeepLink(getIntent())` in `onCreate()`.

## Gallery QR scanning
- User picks image via `Intent.ACTION_PICK` using `ActivityResultLauncher<Intent>`.
- Decoded on a background thread using ZXing `QRCodeReader.decode(BinaryBitmap)`.
- `RGBLuminanceSource` built from `Bitmap.getPixels()`.
- On success, the decoded text is set in `etPartnerCode` and `startAddContact()` called.

## Share ID
- `shareMyId()` creates a plain-text share intent with both the raw ID and the deep link.
- Uses Android's native share sheet (`Intent.createChooser`).

## Clipboard auto-paste
- `tryPasteFromClipboard()` called in `onCreate()`.
- Only pastes if clipboard text matches the ID regex and the field is currently empty.

## Buttons in layout
- `btnCopyCode` — copy own ID
- `btnShowQr` — show QR dialog
- `btnShare` — share via Android share sheet (Tab 0)
- `btnScanQr` — camera scan (Tab 1)
- `btnGallery` — gallery QR pick (Tab 1)
