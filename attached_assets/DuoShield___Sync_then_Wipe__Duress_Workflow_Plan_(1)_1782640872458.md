# DuoShield: "Sync then Wipe" Duress Workflow Plan

This document outlines the technical implementation for the upgraded Duress PIN feature. The goal is to ensure that entering a Duress PIN silently preserves all unsynced chats to the cloud and then completely erases the local app state, leaving the device in a "factory reset" appearance.

---

## 1. Core Logic: The "Panic Sync"
The app must perform a final, high-priority synchronization before the local data is destroyed.

### **Implementation in `BackupManager.java`**
Add a new synchronous method: `syncIncrementalSync(Context ctx)`.
- **Purpose**: To be called on the main thread during a duress event.
- **Logic**:
    1.  Read `last_backup_ts` from SharedPreferences.
    2.  Query Room for all messages where `timestamp > last_backup_ts`.
    3.  Encrypt and upload each message to Firestore immediately.
    4.  **Timeout**: Enforce a strict **5-second maximum**. If the sync isn't finished, it must abort and proceed to the wipe to maintain the user's safety and plausible deniability.

---

## 2. The "Secure Wipe" Execution
The `DuressManager.performLogout()` method will be upgraded from a simple session clear to a total local destruction.

### **Implementation in `DuressManager.java`**
Modify `performLogout(Context context)` to follow this sequence:

1.  **Instant Navigation**: Immediately start `SignInActivity` with `FLAG_ACTIVITY_CLEAR_TASK`. This removes the chat screen from the user's view instantly.
2.  **Execution of Panic Sync**: Call `BackupManager.syncIncrementalSync(context)`.
3.  **Destructive Local Wipe**:
    - **Database**: Call `AppDatabase.clearInstance()` followed by `context.deleteDatabase("duoshield_db")`. This deletes all messages, contacts, and logs.
    - **Keys**: Call `SecurePrefs.get(context).edit().clear().commit()`. Using `.commit()` ensures the Signal identity and backup keys are destroyed synchronously.
    - **Contact Backups**: Call `ContactBackupHelper.clearBackup(context)` to ensure the local "Restore Contacts" feature is also wiped.
    - **Settings**: Clear all `SharedPreferences` files (`duoshield_prefs`, etc.).
4.  **Final Sign-Out**: Call `FirebaseAuth.getInstance().signOut()`.

---

## 3. UI Integration & Deniability
The transition must be indistinguishable from a normal (if slightly slow) PIN verification.

### **Implementation in `LockScreenActivity.java`**
- When `DuressManager.isDuressPin()` returns `true`:
    - The UI remains on the "Verifying..." state.
    - The `performLogout()` sequence is triggered in the background.
    - To an observer, it looks like the app is simply processing the PIN before it "glitches" or "resets" to the login screen.

---

## 4. Recovery Path
The user can recover their data once they are in a safe environment.

1.  **Login**: User opens the app (which looks brand new).
2.  **Restore**: User selects "Restore Account" and enters their **12-word seed phrase**.
3.  **Sync**: The `RestoreFromSeedActivity` re-derives the keys and pulls all chats (including the ones from the "Panic Sync") back from Firestore.
4.  **Result**: The user's account is fully restored to its state prior to the duress event.

---

## 5. Security Guarantees
- **No Cloud Deletion**: The wipe operation never sends delete commands to Firestore.
- **Forensic Resistance**: By clearing `SecurePrefs` and deleting the SQLCipher database file, no plaintext or keys remain on the NAND flash.
- **Plausible Deniability**: An intruder who forces a second login will see an empty account, supporting the user's claim that the app was unconfigured or recently reset.
