package com.successor.engine.algo;

/**
 * A Bloom filter: a bit array of m bits and k hash functions. {@code add(x)} sets the k bits h_1(x)…h_k(x);
 * {@code mightContain(x)} is false only if one of them is still 0.
 *
 * <p><b>Guarantee:</b> no false negatives (an added key's bits stay set). A key never added looks present only if all
 * k of its bits were set by others. After n insertions the probability is about {@code (1 − e^(−kn/m))^k}, minimised
 * by {@code k = (m/n) ln 2}, giving {@code m = −n ln p / (ln 2)²} bits for a target rate p. So a "maybe" answer must
 * always be confirmed against the exact data; a "no" answer never needs confirming.
 *
 * <p><b>Hashing:</b> two 64-bit hashes from one pass over the characters (FNV-1a, then SplitMix64 finalisers with
 * different constants) and double hashing {@code g_i = h1 + i·h2 mod m} (Kirsch &amp; Mitzenmacher), which keeps the
 * same asymptotic false-positive rate as k independent hashes. Time O(|key| + k) per operation; space m bits.
 */
public final class BloomFilter {
    private static final double LN2 = Math.log(2);

    private final long[] words;
    private final long bits;
    private final int hashes;
    private long inserted;

    public BloomFilter(long bits, int hashes) {
        if (bits < 1 || bits > (long) Integer.MAX_VALUE * 64) {
            throw new IllegalArgumentException("bits must be in [1, 2^37], got " + bits);
        }
        if (hashes < 1 || hashes > 30) {
            throw new IllegalArgumentException("hashes must be in [1, 30], got " + hashes);
        }
        this.bits = bits;
        this.hashes = hashes;
        this.words = new long[(int) ((bits + 63) >>> 6)];
    }

    /** A filter sized for {@code expected} keys at false-positive rate {@code fpp}. */
    public static BloomFilter forCapacity(long expected, double fpp) {
        if (expected < 1) {
            throw new IllegalArgumentException("expected keys must be ≥ 1, got " + expected);
        }
        if (!(fpp > 0 && fpp < 1)) {
            throw new IllegalArgumentException("false-positive rate must be in (0, 1), got " + fpp);
        }
        long m = Math.max(64, (long) Math.ceil(-expected * Math.log(fpp) / (LN2 * LN2)));
        int k = (int) Math.max(1, Math.min(30, Math.round((double) m / expected * LN2)));
        return new BloomFilter(m, k);
    }

    public void add(String key) {
        long h1 = hash1(key);
        long h2 = hash2(h1, key) | 1; // odd step: probes never repeat a bit when m is a power of two
        for (int i = 0; i < hashes; i++) {
            long idx = Long.remainderUnsigned(h1 + i * h2, bits);
            words[(int) (idx >>> 6)] |= 1L << idx;
        }
        inserted++;
    }

    /** {@code false} = definitely never added; {@code true} = possibly added (confirm exactly). */
    public boolean mightContain(String key, Work work) {
        long h1 = hash1(key);
        long h2 = hash2(h1, key) | 1;
        for (int i = 0; i < hashes; i++) {
            if (work != null) {
                work.inc();
            }
            long idx = Long.remainderUnsigned(h1 + i * h2, bits);
            if ((words[(int) (idx >>> 6)] & (1L << idx)) == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean mightContain(String key) {
        return mightContain(key, null);
    }

    /** Theoretical false-positive rate for the keys inserted so far. */
    public double expectedFpp() {
        return Math.pow(1 - Math.exp(-(double) hashes * inserted / bits), hashes);
    }

    public long bitSize() {
        return bits;
    }

    public int hashCount() {
        return hashes;
    }

    public long inserted() {
        return inserted;
    }

    public long sizeBytes() {
        return words.length * 8L;
    }

    /**
     * First hash: 64-bit FNV-1a over the string's UTF-16 code units (no byte array is allocated), then a SplitMix64
     * finaliser so every input bit affects every output bit.
     */
    static long hash1(String key) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001B3L;
        }
        return mix(h);
    }

    /** Second, independent hash: the length and the first hash re-mixed with a different constant. */
    static long hash2(long h1, String key) {
        return mix(h1 ^ (0xC2B2AE3D27D4EB4FL * (key.length() + 1)));
    }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
