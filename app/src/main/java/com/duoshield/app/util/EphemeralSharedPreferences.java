package com.duoshield.app.util;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link SharedPreferences} that never touches disk.
 *
 * <h3>Why this exists (S08-H5)</h3>
 * {@link SecurePrefs} used to fall back to plaintext {@code MODE_PRIVATE}
 * prefs when all hardware/software Keystore tiers failed. That silently
 * downgraded every secret the app holds — the SQLCipher passphrase, the
 * Signal identity key, the PIN hash — to a world-readable-by-root XML file,
 * and the only signal was a boolean that most callers ignored. An attacker
 * with a root shell or an adb backup on such a device got the database key
 * verbatim.
 *
 * <p>This class is the fail-closed replacement. It satisfies the
 * {@code SharedPreferences} contract so existing callers compile and run
 * unchanged, but every value lives only in this process's heap: it is gone
 * on process death and is never written to a file, so there is no at-rest
 * artifact to steal.
 *
 * <h3>Consequences callers must understand</h3>
 * <ul>
 *   <li>Values do <strong>not</strong> survive a process restart. Anything
 *       stored here must be re-derivable (e.g. from the user's PIN) or the
 *       feature that depends on it must be blocked outright — see
 *       {@link DeviceSecurityGate}.</li>
 *   <li>{@code commit()} returns {@code true} because the write did succeed
 *       into memory. Callers that use the return value as a durability
 *       guarantee (as {@code DatabaseKeyProvider} does) must additionally
 *       consult {@link SecurePrefs#getTier()} rather than trusting it.</li>
 * </ul>
 *
 * <p>Deliberately package-private constructor: instances should only ever be
 * created by {@link SecurePrefs}, so that the tier it reports and the store it
 * hands out cannot disagree.
 */
final class EphemeralSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners =
            new CopyOnWriteArrayList<>();

    EphemeralSharedPreferences() {}

    @Override
    public synchronized Map<String, ?> getAll() {
        return new LinkedHashMap<>(values);
    }

    @Nullable
    @Override
    public synchronized String getString(String key, @Nullable String defValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object v = values.get(key);
        // Defensive copy: the SharedPreferences contract warns callers not to
        // mutate the returned set, but returning our own instance would let a
        // caller corrupt our state through that documented-but-easy mistake.
        return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object v = values.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object v = values.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @NonNull
    @Override
    public Editor edit() {
        return new EphemeralEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        if (l != null) listeners.remove(l);
    }

    private void notifyChanged(Set<String> changedKeys) {
        for (OnSharedPreferenceChangeListener l : listeners) {
            for (String key : changedKeys) {
                l.onSharedPreferenceChanged(this, key);
            }
        }
    }

    /**
     * Buffers mutations and applies them atomically, matching the real
     * implementation's semantics closely enough that callers cannot tell the
     * difference (other than durability, which is the whole point).
     */
    private final class EphemeralEditor implements Editor {

        private final Map<String, Object> pending = new LinkedHashMap<>();
        private final Set<String>         removals = new HashSet<>();
        private boolean                   clearAll = false;

        @Override public Editor putString(String key, @Nullable String value) {
            return put(key, value);
        }
        @Override public Editor putStringSet(String key, @Nullable Set<String> values) {
            return put(key, values == null ? null : new HashSet<>(values));
        }
        @Override public Editor putInt(String key, int value)         { return put(key, value); }
        @Override public Editor putLong(String key, long value)       { return put(key, value); }
        @Override public Editor putFloat(String key, float value)     { return put(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }

        private Editor put(String key, @Nullable Object value) {
            synchronized (this) {
                // A null value means "remove", per the SharedPreferences contract.
                if (value == null) {
                    removals.add(key);
                    pending.remove(key);
                } else {
                    pending.put(key, value);
                    removals.remove(key);
                }
            }
            return this;
        }

        @Override public Editor remove(String key) {
            synchronized (this) {
                removals.add(key);
                pending.remove(key);
            }
            return this;
        }

        @Override public Editor clear() {
            synchronized (this) { clearAll = true; }
            return this;
        }

        @Override public boolean commit() {
            Set<String> changed = new HashSet<>();
            synchronized (EphemeralSharedPreferences.this) {
                if (clearAll) {
                    changed.addAll(values.keySet());
                    values.clear();
                }
                for (String key : removals) {
                    if (values.remove(key) != null) changed.add(key);
                }
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    values.put(e.getKey(), e.getValue());
                    changed.add(e.getKey());
                }
            }
            notifyChanged(changed);
            // True: the write genuinely succeeded — into memory. See the class
            // javadoc for why this must not be read as a durability guarantee.
            return true;
        }

        @Override public void apply() { commit(); }
    }

    /** Test-only view of the backing map, so tests can assert nothing leaked to disk. */
    synchronized Map<String, Object> snapshotForTests() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
