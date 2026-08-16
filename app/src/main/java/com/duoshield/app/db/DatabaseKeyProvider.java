package com.duoshield.app.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.util.SecurePrefs;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Manages the SQLCipher full-database encryption passphrase (BUG-D01).
 *
 * <p>A random 32-byte key is generated on first launch and stored in
 * EncryptedSharedPreferences so it survives app restarts but is protected
 * by the Android Keystore.
 *
 * <h3>Durability contract (SEC-D02)</h3>
 * The passphrase is the <em>only</em> thing standing between the user and
 * total loss of their message history: {@link AppDatabase} deletes and
 * recreates the database whenever SQLCipher cannot open it. Two failure
 * modes previously caused silent, unrecoverable data loss:
 *
 * <ol>
 *   <li><b>Non-durable write.</b> The key was persisted with
 *       {@code apply()}, which returns before the value reaches disk. If the
 *       process was killed between "database created" and "key flushed",
 *       the next launch found no key, minted a different one, failed to
 *       open the database, and wiped it. Now persisted with
 *       {@code commit()} and read back before the key is returned, so the
 *       key is guaranteed on disk before any database file exists.</li>
 *   <li><b>Temporarily unreadable key.</b> {@link SecurePrefs}'s tier-3
 *       recovery path deletes the master-key alias, which makes previously
 *       stored ciphertext unreadable. (It used to also fall back to a
 *       plaintext store; that tier no longer exists — see
 *       {@link SecurePrefs.SecurityTier}.) The old code treated "cannot read key" as
 *       "first run" and minted a fresh key, which guaranteed the database
 *       got destroyed even though the data was still intact and would have
 *       been readable on a later boot when Keystore recovered. Now we only
 *       mint a new key when there is no database to lose; otherwise we
 *       raise {@link KeyUnavailableException} so the caller can retry
 *       instead of destroying data.</li>
 * </ol>
 *
 * <p>Callers must zero the returned array after passing it to
 * {@link net.sqlcipher.database.SupportFactory}.
 */
public final class DatabaseKeyProvider {

    private static final String TAG           = "DatabaseKeyProvider";
    private static final String KEY_DB_CIPHER = "db_cipher_key_v1";
    private static final int    KEY_BYTES     = 32;

    /** Name must stay in sync with {@link AppDatabase}'s Room database name. */
    static final String DB_NAME = "duoshield_db";

    private DatabaseKeyProvider() {}

    /**
     * Thrown when an encrypted database exists but its passphrase cannot be
     * read right now. This is a <em>recoverable</em> condition — the caller
     * must NOT delete the database, because the key may become readable
     * again once the Android Keystore recovers.
     */
    public static class KeyUnavailableException extends IllegalStateException {
        public KeyUnavailableException(String message) { super(message); }
    }

    public static byte[] getOrCreate(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        SharedPreferences prefs = SecurePrefs.get(appCtx);

        byte[] existing = readKey(prefs);
        if (existing != null) return existing;

        // No usable key. Decide whether this is a genuine first run or a lost key.
        if (databaseExists(appCtx)) {
            // A database exists but we cannot read its passphrase. Minting a new
            // key here would cause AppDatabase to delete every message the user
            // has. Refuse, and let the caller decide (retry / surface an error).
            throw new KeyUnavailableException(
                    "Encrypted database exists but its passphrase could not be read"
                            + " (tier=" + SecurePrefs.getTier() + ")."
                            + " Refusing to mint a replacement key, which would destroy"
                            + " the existing database.");
        }

        // Genuine first run. Refuse to mint a key we know cannot be persisted:
        // on SecurityTier.NONE the store is in-memory only, so the key would be
        // lost at process death, and the very next launch would hit the branch
        // above — a database that exists with an unreadable passphrase, i.e. a
        // permanently unopenable database. Failing here instead means no database
        // is ever created on such a device, so there is nothing to lose.
        //
        // DeviceSecurityGate is what keeps users from reaching this point; this
        // check is the backstop for any entry point that forgets to consult it.
        if (!SecurePrefs.getTier().isDurable()) {
            throw new KeyUnavailableException(
                    "Refusing to create a database key on a device with no working"
                            + " Keystore tier (tier=" + SecurePrefs.getTier() + ")."
                            + " The key could not be persisted, so the database would be"
                            + " unopenable after process death. See DeviceSecurityGate.");
        }

        return createAndPersist(prefs);
    }

    /**
     * Reads and validates the stored passphrase.
     *
     * @return the 32-byte key, or {@code null} if absent/corrupt.
     */
    private static byte[] readKey(SharedPreferences prefs) {
        String stored;
        try {
            stored = prefs.getString(KEY_DB_CIPHER, null);
        } catch (Exception e) {
            // EncryptedSharedPreferences throws when the underlying master key
            // can no longer decrypt this file (rotated / deleted alias).
            Log.e(TAG, "Failed to read stored DB key: " + e.getClass().getSimpleName());
            return null;
        }
        if (stored == null) return null;

        byte[] key;
        try {
            key = Base64.decode(stored, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Stored DB key is not valid Base64 — treating as absent.");
            return null;
        }
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            Log.e(TAG, "Stored DB key has wrong length — treating as absent.");
            return null;
        }
        return key;
    }

    /**
     * Generates a fresh key and persists it <em>durably</em> before returning,
     * so the key can never be newer than the database it protects.
     */
    private static byte[] createAndPersist(SharedPreferences prefs) {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.encodeToString(key, Base64.NO_WRAP);

        // commit() (not apply()) — must reach disk before the database is created.
        boolean committed;
        try {
            committed = prefs.edit().putString(KEY_DB_CIPHER, encoded).commit();
        } catch (Exception e) {
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "Failed to persist new database key: " + e.getClass().getSimpleName());
        }
        if (!committed) {
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "Failed to persist new database key (commit returned false).");
        }

        // Read back to confirm the value is retrievable, not just written. This
        // catches an ESP store that accepts writes but cannot decrypt its own
        // output — which would otherwise surface later as a wiped database.
        byte[] verify = readKey(prefs);
        if (verify == null || !Arrays.equals(verify, key)) {
            if (verify != null) Arrays.fill(verify, (byte) 0);
            Arrays.fill(key, (byte) 0);
            throw new KeyUnavailableException(
                    "New database key failed read-back verification.");
        }
        Arrays.fill(verify, (byte) 0);

        if (SecurePrefs.getTier() == SecurePrefs.SecurityTier.SOFTWARE) {
            Log.w(TAG, "DB passphrase stored under a software-backed Keystore key:"
                    + " encrypted at rest, but the key material may be extractable"
                    + " given a compromised OS image.");
        }
        return key;
    }

    /** True if a Room/SQLCipher database file already exists on disk. */
    private static boolean databaseExists(Context appCtx) {
        File db = appCtx.getDatabasePath(DB_NAME);
        if (db != null && db.exists() && db.length() > 0) return true;
        // WAL mode: the main file can be zero-length while data sits in the -wal.
        File wal = new File(db == null ? "" : db.getPath() + "-wal");
        return wal.exists() && wal.length() > 0;
    }
}
