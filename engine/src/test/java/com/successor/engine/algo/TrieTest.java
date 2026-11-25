package com.successor.engine.algo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrieTest {

    @Test
    void findsEveryKeyWithThePrefixAndNothingElse() {
        Trie t = new Trie();
        t.insert("kevin de bruyne", 0);
        t.insert("de bruyne", 0);
        t.insert("bruyne", 0);
        t.insert("kevin mbabu", 1);
        t.insert("rayan cherki", 2);
        t.insert("mathis cherki", 2);
        t.insert("cherki", 2);
        assertArrayEquals(new int[]{0}, sorted(t.prefix("bru", null)));
        assertArrayEquals(new int[]{0, 1}, sorted(t.prefix("kevin", null)));
        assertArrayEquals(new int[]{2}, sorted(t.prefix("cher", null)), "item 2 appears once despite three keys");
        assertArrayEquals(new int[]{2}, sorted(t.prefix("mathis", null)));
    }

    @Test
    void agreesWithALinearScanOnRandomKeys() {
        Random rnd = new Random(5);
        List<String> keys = new ArrayList<>();
        Trie t = new Trie();
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(8);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + rnd.nextInt(4)));
            }
            keys.add(sb.toString());
            t.insert(sb.toString(), i);
        }
        for (int q = 0; q < 300; q++) {
            String key = keys.get(rnd.nextInt(keys.size()));
            String p = key.substring(0, Math.min(key.length(), 1 + rnd.nextInt(2)));
            TreeSet<Integer> expected = new TreeSet<>();
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).startsWith(p)) {
                    expected.add(i);
                }
            }
            int[] got = sorted(t.prefix(p, null));
            assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(), got, "prefix " + p);
        }
    }

    @Test
    void edgeCasesEmptyPrefixUnknownPrefixAndEmptyTrie() {
        Trie empty = new Trie();
        assertEquals(0, empty.prefix("a", null).length);
        assertEquals(0, empty.prefix("", null).length);
        Trie t = new Trie();
        t.insert("lamine yamal", 7);
        t.insert("", 8); // empty key is legal and found by the empty prefix
        assertArrayEquals(new int[]{7, 8}, sorted(t.prefix("", null)));
        assertEquals(0, t.prefix("zz", null).length);
        assertEquals(0, t.prefix("lamine yamal jr", null).length, "prefix longer than every key");
        assertTrue(t.hasPrefix("lam"));
        assertFalse(t.hasPrefix("lax"));
    }

    @Test
    void duplicatePairsAreStoredOnce() {
        Trie t = new Trie();
        t.insert("pedri", 3);
        t.insert("pedri", 3);
        t.insert("pedri", 4);
        assertEquals(2, t.size());
        assertArrayEquals(new int[]{3, 4}, sorted(t.prefix("ped", null)));
    }

    @Test
    void workDependsOnTheMatchesNotOnTheDictionarySize() {
        Trie t = new Trie();
        for (int i = 0; i < 20_000; i++) {
            t.insert("player" + i, i);
        }
        t.insert("odegaard", 99_999);
        Work w = Work.counter();
        assertArrayEquals(new int[]{99_999}, t.prefix("ode", w));
        assertTrue(w.get() < 40, "a rare prefix touches a handful of nodes, not 20,000 keys: " + w.get());
    }

    @Test
    void invalidInputIsRejected() {
        Trie t = new Trie();
        assertThrows(IllegalArgumentException.class, () -> t.insert(null, 1));
        assertThrows(IllegalArgumentException.class, () -> t.insert("x", -1));
    }

    private static int[] sorted(int[] a) {
        int[] b = a.clone();
        Arrays.sort(b);
        return b;
    }
}
