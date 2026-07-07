---
name: DuoShield OneSignal FCM service conflict
description: How DuoShieldMessagingService conflicts with OneSignal SDK 5.x FCM routing, and the fix applied.
---

## The problem

`DuoShieldMessagingService` extends `FirebaseMessagingService` and is declared in the **app manifest**, giving it higher FCM routing priority than OneSignal's library-merged `OneSignalFCMService`. This means ALL FCM messages — including OneSignal's own internal token-registration and notification messages — were intercepted by DuoShield's service and never forwarded to OneSignal. Result: OneSignal dashboard shows "Your user failed to be subscribed — SDK never received a response."

## The fix

Added an early-return guard at the TOP of `DuoShieldMessagingService.onMessageReceived()`:

```java
if (remoteMessage.getData().containsKey("os_data")) {
    Log.d(TAG, "Routing OneSignal FCM message to SDK pipeline.");
    return;
}
```

**Why `os_data`:** OneSignal SDK 5.x embeds this key in every FCM message it sends (token registration, notification delivery, in-app messages, etc.). DuoShield's own peer-to-peer messages use `chatId` + `messageId` keys — never `os_data`.

**Why:** App-manifest services win the priority battle over library-manifest services. Returning early lets OneSignal's WorkManager pipeline process the message as intended.

## Secondary dashboard fix required

Even with the code fix, OneSignal ALSO needs valid FCM credentials in its dashboard:
- **Wrong file:** `google-services.json` (client-side, used by the app) → gives "Invalid request"
- **Correct file:** Firebase Admin SDK service account private key (downloaded from Firebase Console → Project Settings → Service Accounts → Generate new private key)
- The correct JSON contains `"type": "service_account"`, `private_key`, `client_email` fields

## Files changed

- `notifications/DuoShieldMessagingService.java` — `os_data` guard added to `onMessageReceived()`

## Token refresh

OneSignal SDK 5.x fetches its own FCM token via `FirebaseMessaging.getInstance().getToken()` on init — it does NOT rely on `onNewToken()` being forwarded. So no `onNewToken` forwarding is needed.
