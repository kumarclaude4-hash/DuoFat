package com.duoshield.app.db;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import android.content.Context;
import android.util.Log;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.duoshield.app.models.SignalSessionRecord;
import com.duoshield.app.db.CallRecord;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

@Database(
    entities = {
        Message.class, SignalSessionRecord.class,
        Contact.class, Group.class, GroupMember.class, CallRecord.class
    },
    version = 22
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract MessageDao      messageDao();
    public abstract SignalSessionDao signalSessionDao();
    public abstract ContactDao      contactDao();
    public abstract GroupDao        groupDao();
    public abstract CallHistoryDao  callHistoryDao();

    private static final String TAG = "AppDatabase";

    /**
     * BUG-D01 fix: open the Room database with SQLCipher AES-256 full-database
     * encryption.  The passphrase is a random 32-byte key generated on first
     * launch and stored in EncryptedSharedPreferences (see {@link DatabaseKeyProvider}).
     *
     * <p>If the database file was created before this change (plain SQLite),
     * SQLCipher cannot open it and throws an exception.  In that case we delete
     * the old unencrypted file and create a fresh encrypted one.  For a security
     * app this is the correct trade-off: privacy is worth more than migrating an
     * old plaintext cache.
     */
    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            SQLiteDatabase.loadLibs(context);
            byte[] passphrase = DatabaseKeyProvider.getOrCreate(context);
            SupportFactory factory = new SupportFactory(passphrase);
            java.util.Arrays.fill(passphrase, (byte) 0); // zero out after handing to factory
            try {
                instance = buildDatabase(context.getApplicationContext(), factory);
                // Force-open to detect a pre-existing unencrypted file early
                instance.getOpenHelper().getWritableDatabase();
            } catch (Exception e) {
                Log.w(TAG, "SQLCipher could not open existing DB — deleting and recreating "
                        + "encrypted (BUG-D01 first-run upgrade)", e);
                try { instance.close(); } catch (Exception ignored) {}
                instance = null;
                context.deleteDatabase("duoshield_db");
                // Re-derive passphrase (was zeroed above)
                byte[] p2 = DatabaseKeyProvider.getOrCreate(context);
                SupportFactory f2 = new SupportFactory(p2);
                java.util.Arrays.fill(p2, (byte) 0);
                instance = buildDatabase(context.getApplicationContext(), f2);
            }
        }
        return instance;
    }

    private static AppDatabase buildDatabase(Context appCtx, SupportFactory factory) {
        return Room.databaseBuilder(appCtx, AppDatabase.class, "duoshield_db")
            .openHelperFactory(factory)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            .build();
    }

    /**
     * Closes and nulls the singleton so the next {@link #getInstance} call opens a
     * fresh connection.  Must be called immediately after {@code ctx.deleteDatabase()}
     * in every wipe or duress-logout path — otherwise the cached singleton points to
     * the deleted file and subsequent DAO calls silently fail or recreate a stale DB.
     */
    public static synchronized void clearInstance() {
        if (instance != null) {
            try { instance.close(); } catch (Exception ignored) {}
            instance = null;
        }
    }

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS Message");
            db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY," +
                " conversationId TEXT, sender TEXT, text TEXT," +
                " timestamp INTEGER NOT NULL DEFAULT 0, isEncrypted INTEGER NOT NULL DEFAULT 0)");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaUrl TEXT");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaType TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN seen INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyPreview TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS session_events " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "event_type TEXT, " +
                "timestamp INTEGER NOT NULL DEFAULT 0, " +
                "device_model TEXT, " +
                "android_version TEXT)");
        }
    };

    // v9: Signal Protocol session store.
    // address = "{firebaseUid}.{deviceId}" (always deviceId=1 for DuoShield's 1-to-1 model).
    // session_data = raw bytes of a serialised libsignal SessionRecord (Double Ratchet state).
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS signal_sessions (" +
                "address TEXT NOT NULL PRIMARY KEY, " +
                "session_data BLOB NOT NULL, " +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
        }
    };

    // v10: Signal message type column.
    // sigType = 0 → legacy ECDH (no Signal); 2 → SignalMessage (WHISPER); 3 → PreKeySignalMessage.
    // NOT NULL DEFAULT 0 ensures all existing rows get the legacy-ECDH sentinel automatically.
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN sigType INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v11: Per-media AES-256-GCM key column.
    // mediaKey = Base64-encoded 32-byte AES key generated per upload.
    // NULL for legacy messages (plaintext media from before this version).
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaKey TEXT");
        }
    };

    // v12: Group conversations.
    // contacts   — contacts added via ContactManager.addContact().
    // groups     — group conversations with shared AES-256 group key.
    // group_members — per-group member roster.
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `contacts` (" +
                "`uid` TEXT NOT NULL, " +
                "`displayName` TEXT, " +
                "`conversationId` TEXT, " +
                "`avatarUrl` TEXT, " +
                "PRIMARY KEY(`uid`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `groups` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`avatarUrl` TEXT, " +
                "`createdBy` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`groupKey` TEXT, " +
                "`lastMessage` TEXT, " +
                "`lastMessageTs` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_members` (" +
                "`groupId` TEXT NOT NULL, " +
                "`memberUid` TEXT NOT NULL, " +
                "`displayName` TEXT, " +
                "`joinedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`groupId`, `memberUid`))");
        }
    };

    // v13: Starred messages.
    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v18: Persist voice-note waveform amplitudes so a chat reopened from local
    // Room cache shows the real recorded/received waveform instead of a
    // synthetic fake one (amplitudes was previously @Ignore-d).
    static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN amplitudes TEXT");
        }
    };

    // v19: Persist voice-note total duration so bubbles show the real recorded
    // length at rest instead of a static "0:00" placeholder.
    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v14: Soft delete. isDeleted = 1 hides a message from the UI and excludes it
    // from backups without removing the Room row (preserves Signal ratchet continuity).
    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v17: Remove session_events table (session logging removed — privacy principle).
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS session_events");
        }
    };

    // v16: Add call_history table for local-only call log.
    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS call_history (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "partnerId TEXT NOT NULL, " +
                    "partnerName TEXT NOT NULL, " +
                    "isVideo INTEGER NOT NULL DEFAULT 0, " +
                    "direction TEXT NOT NULL, " +
                    "outcome TEXT NOT NULL, " +
                    "startedAt INTEGER NOT NULL, " +
                    "durationSeconds INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_call_history_startedAt ON call_history (startedAt)");
        }
    };

    // v21: Add forwarded column to messages — tracks messages forwarded from other conversations.
    static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            // caption: text caption shown beneath a photo/video in the same bubble
            db.execSQL("ALTER TABLE messages ADD COLUMN caption TEXT NOT NULL DEFAULT ''");
            // media_items: JSON array for multi-photo/video album messages
            db.execSQL("ALTER TABLE messages ADD COLUMN media_items TEXT");
        }
    };

    static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0");
        }
    };

    // v20: Add indices on contacts(displayName) and signal_sessions(address).
    // contacts is loaded with ORDER BY displayName ASC on every list open; without an index
    // SQLite does a full table scan + sort. signal_sessions is looked up by address on every
    // Signal encrypt/decrypt; the index turns that from O(n) to O(log n).
    static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_displayName ON contacts (displayName)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_signal_sessions_address ON signal_sessions (address)");
        }
    };

    // v15: Performance optimization — add secondary indices on frequently queried columns.
    // These indices dramatically speed up message retrieval, expiry cleanup, and status queries
    // without changing the schema or breaking encryption/disappearing message logic.
    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            // Composite index for efficient message retrieval by conversation and timestamp
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages (conversationId, timestamp)");
            // Index for efficient expiry cleanup in SelfDestructWorker
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_expiresAt ON messages (expiresAt)");
            // Index for efficient undelivered message queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status_sender ON messages (status, sender)");
            // Index for efficient group member lookups
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)");
            // Index for efficient group queries by creation timestamp
            db.execSQL("CREATE INDEX IF NOT EXISTS index_groups_createdAt ON groups (createdAt)");
        }
    };
}
