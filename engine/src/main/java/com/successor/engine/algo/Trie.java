package com.successor.engine.algo;

import java.util.Arrays;

/**
 * A trie (prefix tree) from strings to integer item ids, for type-ahead search. Each node is one character; the path
 * from the root spells a prefix; the ids of every key that ends at a node are stored there.
 *
 * <pre>
 *   insert(key, id):  walk/create one node per character, add id to the last node        O(|key|)
 *   prefix(p):        walk |p| nodes, then collect the ids in that node's subtree          O(|p| + subtree)
 * </pre>
 * A linear scan compares the prefix against every key, O(n·|p|) per query. The trie touches only the keys that
 * actually start with the prefix, which is why type-ahead stays instant as the dataset grows.
 *
 * <p>Storage is flat arrays (first-child / next-sibling lists, postings lists), so 100k keys cost a few MB and no
 * per-node objects. Keys are taken as given: callers normalise them (lower case, accents removed) before inserting and
 * before searching.
 */
public final class Trie {
    private char[] label = new char[64];
    private int[] firstChild = new int[64];
    private int[] nextSibling = new int[64];
    private int[] firstPosting = new int[64];
    private int nodes = 1; // node 0 = root
    private int[] postItem = new int[64];
    private int[] postNext = new int[64];
    private int postings;
    private int keys;
    private int[] seen = new int[0];
    private int stamp;

    public Trie() {
        firstChild[0] = -1;
        nextSibling[0] = -1;
        firstPosting[0] = -1;
    }

    /** Adds {@code key → item}. Inserting the same pair twice stores it once. */
    public void insert(String key, int item) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (item < 0) {
            throw new IllegalArgumentException("item ids must be ≥ 0, got " + item);
        }
        int node = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            int child = child(node, c, null);
            if (child < 0) {
                child = newNode(c);
                nextSibling[child] = firstChild[node];
                firstChild[node] = child;
            }
            node = child;
        }
        for (int p = firstPosting[node]; p != -1; p = postNext[p]) {
            if (postItem[p] == item) {
                return;
            }
        }
        if (postings == postItem.length) {
            postItem = Arrays.copyOf(postItem, postings * 2);
            postNext = Arrays.copyOf(postNext, postings * 2);
        }
        postItem[postings] = item;
        postNext[postings] = firstPosting[node];
        firstPosting[node] = postings++;
        keys++;
    }

    /**
     * Distinct ids of all keys starting with {@code prefix}, in no particular order. The empty prefix returns every id.
     * Work counts nodes visited.
     */
    public int[] prefix(String prefix, Work work) {
        int node = 0;
        for (int i = 0; i < prefix.length() && node >= 0; i++) {
            node = child(node, prefix.charAt(i), work);
        }
        if (node < 0) {
            return new int[0];
        }
        stamp++;
        int[] out = new int[16];
        int n = 0;
        int[] stack = new int[32];
        int sp = 0;
        stack[sp++] = node;
        while (sp > 0) {
            int u = stack[--sp];
            if (work != null) {
                work.inc();
            }
            for (int p = firstPosting[u]; p != -1; p = postNext[p]) {
                int item = postItem[p];
                if (item >= seen.length) {
                    seen = Arrays.copyOf(seen, Math.max(item + 1, seen.length * 2));
                }
                if (seen[item] != stamp) {
                    seen[item] = stamp;
                    if (n == out.length) {
                        out = Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = item;
                }
            }
            for (int c = firstChild[u]; c != -1; c = nextSibling[c]) {
                if (sp == stack.length) {
                    stack = Arrays.copyOf(stack, sp * 2);
                }
                stack[sp++] = c;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** True if some key starts with {@code prefix}. O(|prefix|). */
    public boolean hasPrefix(String prefix) {
        int node = 0;
        for (int i = 0; i < prefix.length() && node >= 0; i++) {
            node = child(node, prefix.charAt(i), null);
        }
        return node >= 0;
    }

    public int nodeCount() {
        return nodes;
    }

    /** Number of distinct (key, item) pairs stored. */
    public int size() {
        return keys;
    }

    /** Approximate heap bytes used by the arrays. */
    public long sizeBytes() {
        return (long) label.length * 2 + (long) firstChild.length * 12 + (long) postItem.length * 8;
    }

    private int child(int node, char c, Work work) {
        for (int ch = firstChild[node]; ch != -1; ch = nextSibling[ch]) {
            if (work != null) {
                work.inc();
            }
            if (label[ch] == c) {
                return ch;
            }
        }
        return -1;
    }

    private int newNode(char c) {
        if (nodes == label.length) {
            int m = nodes * 2;
            label = Arrays.copyOf(label, m);
            firstChild = Arrays.copyOf(firstChild, m);
            nextSibling = Arrays.copyOf(nextSibling, m);
            firstPosting = Arrays.copyOf(firstPosting, m);
        }
        label[nodes] = c;
        firstChild[nodes] = -1;
        nextSibling[nodes] = -1;
        firstPosting[nodes] = -1;
        return nodes++;
    }
}
