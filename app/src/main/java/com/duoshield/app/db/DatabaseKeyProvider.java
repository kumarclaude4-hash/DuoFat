package com.duoshield.app.db;

import android.content.Context;
import android.util.Base64;
import com.duoshield.app.util.SecurePrefs;
import java.security.SecureRandom;

/**
 * Manages the SQLCipher full-database encryption passphrase (BUG-D01).
 *
 * <p>A random 32-byte key is generated on first launch and stored in
 * EncryptedSharedPreferences so it survives app restarts but is protected
 * by the Android Keystore.  If EncryptedSharedPreferences is unavailable
 * (device without a secure element, or EncryptedSharedPreferences init
 * failure) the passphrase falls back to the same storage but without
 * hardware-backed protection — still better than plaintext Room.
 *
 * <p>Callers must zero the returned array after passing it to
 * {@link net.sqlcipher.database.SupportFactory}.
 */
public final class DatabaseKeyProvider {

    private static final String KEY_DB_CIPHER = "db_cipher_key_v1";

    private DatabaseKeyProvider() {}

    public static byte[] getOrCreate(Context ctx) {
        String stored = SecurePrefs.get(ctx).getString(KEY_DB_CIPHER, null);
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP);
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        SecurePrefs.get(ctx).edit()
                .putString(KEY_DB_CIPHER, Base64.encodeToString(key, Base64.NO_WRAP))
                .apply();
        return key;
    }
}
