package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/**
 * Stable, per-install device identifier (S08-H5 item 4c).
 *
 * <h3>Why this exists</h3>
 * {@code users/{uid}.fcmToken} historically held a SINGLE push token, so a new
 * device signing into the account overwrote it ({@link FcmTokenHelper},
 * {@code DuoShieldMessagingService.onNewToken}) — erasing the only surviving
 * notification target. That made the restore-race warning of item 4c a no-op:
 * there was literally no other device to push "a new device is restoring your
 * account" to. This id lets each install own a document under
 * {@code users/{uid}/devices/{deviceId}} so every prior device stays reachable.
 *
 * <h3>What this id is and is NOT</h3>
 * It is a random {@link UUID} minted once per app install and persisted in the
 * app-private prefs. It is deliberately NOT {@code Settings.Secure.ANDROID_ID}
 * or any hardware identifier:
 * <ul>
 *   <li>ANDROID_ID is scoped per signing-key + user and is stable across
 *       reinstalls, which would defeat the "unrecognized device" detection the
 *       server relies on — a wipe-and-restore attacker would present the same
 *       id as the legitimate prior install.</li>
 *   <li>Hardware ids are privacy-sensitive and gated behind permissions on
 *       modern Android; a random per-install UUID needs no permission and leaks
 *       nothing about the physical device.</li>
 * </ul>
 * Because it is regenerated on reinstall, a freshly restored install is
 * correctly seen as a NEW {@code deviceId} by the server, which is exactly the
 * signal the restore-race delay/notify logic keys off of.
 *
 * <p>This value is a routing/registry key only. It is never used as key
 * material and grants no authority on its own — the account is still gated by
 * Firebase Auth ownership and (item 4a) App Check on every {@code devices/}
 * read and write.
 */
public final class DeviceIdProvider {

    private static final String PREFS   = "duoshield_prefs";
    private static final String KEY_ID  = "device_install_id";

    private DeviceIdProvider() { }

    /**
     * Returns this install's device id, minting and persisting one on first use.
     * Thread-safe: synchronized so two concurrent callers on a fresh install
     * cannot mint two different ids and race the persist.
     */
    public static synchronized String get(Context ctx) {
        SharedPreferences prefs =
                ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_ID, id).apply();
        }
        return id;
    }
}
