package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.duoshield.app.db.AppDatabase;

import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupScheduler;
import java.io.File;

public class WipeHelper {

    private static final String TAG = "WipeHelper";

    public static void wipeAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String convId = prefs.getString("conversation_id", null);

        // Step 0: Back up contacts BEFORE destroying the DB key so contacts
        // survive a voluntary Wipe & Exit and can be restored on next sign-in.
        // The backup prefs file is separate from "duoshield_prefs" and is NOT
        // cleared here. DuressManager.performLogout() explicitly wipes it so
        // a security-triggered logout cannot be recovered.
        try {
            String myUid = prefs.getString("my_uid", null);
            if (myUid == null) {
                com.google.firebase.auth.FirebaseUser fu =
                        com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                if (fu != null) myUid = fu.getUid();
            }
            if (myUid != null) {
                java.util.List<com.duoshield.app.models.Contact> contacts =
                        AppDatabase.getInstance(ctx).contactDao().getAll();
                ContactBackupHelper.backup(ctx, myUid, contacts);
            }
        } catch (Exception e) {
            Log.w(TAG, "Contact backup failed (non-fatal) — proceeding with wipe", e);
        }

        // Wipe EncryptedSharedPreferences FIRST so Signal identity key material
        // (identity key pair, prekeys, PIN hashes) is destroyed before anything
        // else. A forensic extraction after wipe must not recover any key material.
        try {
            SecurePrefs.get(ctx).edit().clear().commit();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear SecurePrefs during wipe", e);
        }

        // Close and null the Room singleton BEFORE deleting the file — prevents the
        // cached instance from pointing to the deleted database on the next access.
        AppDatabase.clearInstance();

        // Delete the Room database file directly — ensures complete erasure even
        // without going through the DAO layer.
        try {
            ctx.deleteDatabase("duoshield_db");
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete Room DB file during wipe", e);
        }

        // Cancel scheduled backup sync — no user session means nothing to back up.
        try { BackupScheduler.cancel(ctx); } catch (Exception e) {
            Log.w(TAG, "BackupScheduler.cancel() failed during wipe (non-fatal)", e);
        }

        // Wipe the persistent B2 disk cache — decrypted media must not survive a wipe.
        try { B2StorageHelper.clearDiskCache(ctx); } catch (Exception e) {
            Log.w(TAG, "clearDiskCache() failed during wipe (non-fatal)", e);
        }

        // F37 fix: delete any media files saved to the public gallery (Pictures/DuoShield,
        // Movies/DuoShield) during this session via the Save button.
        try { MediaStoreWipeHelper.wipeAll(ctx); } catch (Exception e) {
            Log.w(TAG, "MediaStoreWipeHelper.wipeAll() failed during wipe (non-fatal)", e);
        }

        // Clear plain prefs last (synchronous, ensures SignInActivity reads empty prefs)
        prefs.edit().clear().commit();

        // F32 fix: Clear the security prefs file (pin_fail_count) so a leftover
        // fail counter from this session cannot carry over into the next account.
        // This file is intentionally NOT cleared by DuressManager.performLogout()
        // (which needs the counter to survive its own wipe), but a voluntary
        // "Wipe & Exit" should leave the device in a clean state.
        ctx.getSharedPreferences("duoshield_security_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();

        // F14 fix: Sign out of Firebase so no valid session token remains after wipe.
        // Without this, a forensic extraction could reuse the token to access Firestore
        // until it expires (~1 hour).
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
        } catch (Exception e) {
            Log.e(TAG, "Firebase signOut failed during wipe (non-fatal)", e);
        }

        try {
            File cache = ctx.getCacheDir();
            deleteDir(cache);
        } catch (Exception ignored) {}

        Intent i = new Intent(ctx, SignInActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    private static void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }
}
