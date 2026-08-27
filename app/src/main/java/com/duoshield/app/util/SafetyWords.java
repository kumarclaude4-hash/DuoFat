package com.duoshield.app.util;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Human-comparable representation of a pair of Signal identity-key fingerprints.
 *
 * <p>Comparing 64 hex digits aloud is the single worst part of the verification UX: it is slow,
 * error-prone, and people give up halfway. This class turns the <em>same underlying key
 * material</em> into two things a human can actually check in a few seconds:
 * <ul>
 *   <li>a short <b>safety-word phrase</b> (see {@link #phrase}), and</li>
 *   <li>a deterministic <b>seed</b> (see {@link #combinedSeed}) that drives the generative
 *       Trust&nbsp;Seal emblem in {@code TrustGlyphView}.</li>
 * </ul>
 *
 * <p><b>Why it is safe to derive a shorter representation.</b> The authoritative check remains the
 * full fingerprint / QR scan, which is unchanged. The words and emblem are a <em>first-line human
 * aid</em>: they let two people notice a mismatch instantly, at which point the full comparison
 * settles it. The derivation is a plain SHA-256 over the key material, so it reveals nothing a
 * fingerprint (itself the hash of a public key) doesn't already.
 *
 * <p><b>Why it is order-independent.</b> Both contacts must arrive at the identical phrase and
 * emblem regardless of who opens the screen. {@link #combinedSeed} sorts the two fingerprint
 * hexes before hashing, so {@code combinedSeed(a, b) == combinedSeed(b, a)}. This mirrors how the
 * Signal protocol builds a shared "safety number" from both identities.
 */
public final class SafetyWords {

    private SafetyWords() {}

    /** Number of words shown in a safety phrase. 6 words &rarr; 48 bits of human-checkable surface. */
    public static final int WORD_COUNT = 6;

    /**
     * 256-word list — one word per byte. Chosen to be short, concrete, and phonetically distinct,
     * avoiding near-homophones (no "meat/meet", "night/knight") so a word read aloud is
     * unambiguous. The list is fixed: changing it changes everyone's phrases, so it must never be
     * reordered or edited once shipped.
     */
    private static final String[] WORDS = {
        "amber","anchor","apple","arrow","atlas","autumn","badge","bamboo",
        "banjo","basket","beacon","beetle","birch","bishop","bison","blossom",
        "bonus","boulder","breeze","bridge","bronze","bubble","buffalo","bugle",
        "cabin","cactus","camel","candle","canyon","cargo","carrot","castle",
        "cedar","cellar","chalk","cherry","chess","chimney","clover","cobra",
        "cocoa","comet","compass","copper","coral","cottage","cougar","crayon",
        "cricket","crimson","crystal","cymbal","dagger","daisy","dawn","delta",
        "denim","diamond","dolphin","domino","donkey","dragon","drum","dune",
        "eagle","ember","emerald","engine","falcon","fable","feather","fennel",
        "fiddle","flamingo","flint","forest","fossil","fountain","foxglove","frost",
        "galaxy","garden","gecko","ginger","glacier","granite","gravel","grotto",
        "guitar","hammer","harbor","harvest","hazel","helmet","hermit","hexagon",
        "hollow","hornet","ingot","island","ivory","jackal","jaguar","jasmine",
        "jelly","jersey","jigsaw","journal","jungle","juniper","kangaroo","kayak",
        "kernel","kettle","keystone","kingdom","kitten","koala","lagoon","lantern",
        "lattice","lava","ledger","lemon","lentil","leopard","lily","lizard",
        "llama","lobster","locket","lotus","lumber","lunar","magnet","mammoth",
        "mango","maple","marble","marigold","meadow","medal","melon","meteor",
        "mineral","mirror","mitten","monarch","mosaic","moth","mustard","nectar",
        "needle","nickel","ocean","octagon","olive","onyx","opal","orbit",
        "orchid","otter","oyster","paddle","panther","papaya","parcel","parsley",
        "pebble","pelican","pepper","petal","pewter","pigeon","pillar","pine",
        "piston","planet","plaza","plume","pocket","pollen","poplar","portal",
        "prairie","prism","pudding","pumpkin","python","quarry","quartz","quilt",
        "rabbit","radar","raisin","ranch","raven","ribbon","ripple","river",
        "robin","rocket","rooster","rubble","ruby","saffron","salmon","sapphire",
        "satchel","saturn","scarlet","scooter","sequoia","shadow","shovel","silver",
        "siren","skylark","slate","sleet","sonar","sparrow","spruce","squid",
        "stallion","sterling","stork","sugar","summit","sunset","syrup","tangerine",
        "temple","thistle","thunder","tiger","timber","topaz","tortoise","totem",
        "tulip","tundra","turquoise","turtle","umbrella","unicorn","valley","velvet",
        "violet","vulture","walnut","walrus","willow","window","yonder","zephyr"
    };

    /**
     * Order-independent 32-byte seed shared by both contacts.
     *
     * @param fingerprintHexA one contact's fingerprint hex (SHA-256 of their identity key)
     * @param fingerprintHexB the other contact's fingerprint hex
     * @return SHA-256 of the two hexes concatenated in sorted order, or {@code null} if either is
     *         missing or hashing is unavailable.
     */
    public static byte[] combinedSeed(String fingerprintHexA, String fingerprintHexB) {
        if (fingerprintHexA == null || fingerprintHexB == null) return null;
        String a = fingerprintHexA.trim().toLowerCase(Locale.ROOT);
        String b = fingerprintHexB.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return null;
        String combined = (a.compareTo(b) <= 0) ? (a + b) : (b + a);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(combined.getBytes("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds the safety-word phrase from a combined seed.
     *
     * @param seed a seed from {@link #combinedSeed}
     * @return e.g. {@code "Amber · Comet · Willow · Ember · Marble · Otter"}, or {@code null} if
     *         the seed is null or too short.
     */
    public static String phrase(byte[] seed) {
        if (seed == null || seed.length < WORD_COUNT) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WORD_COUNT; i++) {
            if (i > 0) sb.append("  \u00B7  ");
            sb.append(capitalize(WORDS[seed[i] & 0xFF]));
        }
        return sb.toString();
    }

    /**
     * Plain, comma-separated phrase for TalkBack, e.g. {@code "Amber, Comet, Willow, ..."}.
     * The mid-dot separator in {@link #phrase} would otherwise be spoken as "dot".
     */
    public static String spokenPhrase(byte[] seed) {
        if (seed == null || seed.length < WORD_COUNT) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WORD_COUNT; i++) {
            if (i > 0) sb.append(", ");
            sb.append(capitalize(WORDS[seed[i] & 0xFF]));
        }
        return sb.toString();
    }

    private static String capitalize(String w) {
        if (w == null || w.isEmpty()) return w;
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
