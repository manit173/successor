# Benchmark results

Java 27.0.1.0.3, Mac OS X aarch64, 10 cores · full sizes · 2026-11-25T04:36:04.279201Z

Times are medians of repeated runs after a warm-up; work counts are exact and machine-independent.

## Player search: trie vs linear scan

500 type-ahead queries (2–4 letter prefixes of real name words) over n made-up player names. The trie walks the prefix and visits only the matching subtree; the linear scan checks every word of every name (names pre-normalised, so the baseline is not charged for that). The third column is the app's full search: trie lookup plus ranking all matches and keeping the top 10 with quickselect. Answer = names matched over all queries.

| names | Trie lookup | Trie + ranking (app search) | Linear scan | answers |
|---|---|---|---|---|
| 10,000 | 2.560 ms · 131,946 nodes visited / words checked · 168360 | 16 ms · 509,241 nodes visited / words checked · 168360 | 67 ms · 11,135,378 nodes visited / words checked · 168360 | agree |
| 100,000 | 18 ms · 254,836 nodes visited / words checked · 1618309 | 92 ms · 3,607,424 nodes visited / words checked · 1618309 | 647 ms · 111,263,027 nodes visited / words checked · 1618309 | agree |
| 1,000,000 | 279 ms · 251,860 nodes visited / words checked · 16175181 | 2,506 ms · 31,303,351 nodes visited / words checked · 16175181 | 6,393 ms · 1,112,247,596 nodes visited / words checked · 16175181 | agree |

## Top 10 of n distances: quickselect vs heap vs sort

The successor search keeps the 10 closest candidates. Quickselect finds the 10th smallest in expected O(n), then sorts 10; the binary heap keeps the best 10 in O(n log k), and for a small k almost every element is rejected after one comparison with the root, so the heap is the fastest here; sorting everything costs O(n log n).

| distances | Quickselect top-k | Binary heap top-k | Sort all | answers |
|---|---|---|---|---|
| 100,000 | 0.726 ms · 213,639 comparisons · 10 ids #8d0ade67 | 0.501 ms · 100,483 comparisons · 10 ids #8d0ade67 | 25 ms · 1,534,936 comparisons · 10 ids #8d0ade67 | agree |
| 1,000,000 | 7.357 ms · 2,380,036 comparisons · 10 ids #df30ac90 | 0.889 ms · 1,000,614 comparisons · 10 ids #df30ac90 | 153 ms · 18,641,004 comparisons · 10 ids #df30ac90 | agree |
| 5,000,000 | 38 ms · 10,516,818 comparisons · 10 ids #cbb99c3d | 4.876 ms · 5,000,659 comparisons · 10 ids #cbb99c3d | 1,016 ms · 105,123,864 comparisons · 10 ids #cbb99c3d | agree |

## Top 10% of n distances: quickselect vs heap vs sort

A shortlist of the closest 10% (e.g. every candidate worth a scout's look). Now k grows with n: the heap costs O(n log k) with log k large, while quickselect stays O(n) + O(k log k) for sorting the winners.

| distances | Quickselect top-k | Binary heap top-k | Sort all | answers |
|---|---|---|---|---|
| 100,000 | 3.492 ms · 346,002 comparisons · 10000 ids #340ba4c3 | 4.242 ms · 871,165 comparisons · 10000 ids #340ba4c3 | 12 ms · 1,534,936 comparisons · 10000 ids #340ba4c3 | agree |
| 1,000,000 | 22 ms · 4,538,001 comparisons · 100000 ids #d8d2c01c | 59 ms · 10,964,640 comparisons · 100000 ids #d8d2c01c | 163 ms · 18,641,004 comparisons · 100000 ids #d8d2c01c | agree |
| 5,000,000 | 137 ms · 22,905,543 comparisons · 500000 ids #3a3066be | 419 ms · 62,273,207 comparisons · 500000 ids #3a3066be | 1,088 ms · 105,123,864 comparisons · 500000 ids #3a3066be | agree |

## Median and quartiles: quickselect vs median-of-medians vs sort

Every cohort needs the median and quartiles of each stat for robust z-scores. One randomized multi-select places all the needed ranks (expected about 2n comparisons) vs deterministic median-of-medians selections (worst-case O(n), bigger constant) vs one full sort (O(n log n), Java's dual-pivot quicksort on a primitive array, no index indirection).

| values | Quickselect (multi-select) | Median of medians ×3 | Sort | answers |
|---|---|---|---|---|
| 100,000 | 2.133 ms · 869,387 comparisons · 1.001696166/2.002025507/3.006104517 | 17 ms · 5,060,743 comparisons · 1.001696166/2.002025507/3.006104517 | 5.337 ms · 1,660,964 comparisons · 1.001696166/2.002025507/3.006104517 | agree |
| 1,000,000 | 22 ms · 7,770,228 comparisons · 1.000738449/1.999986178/2.998970812 | 179 ms · 51,822,076 comparisons · 1.000738449/1.999986178/2.998970812 | 48 ms · 19,931,569 comparisons · 1.000738449/1.999986178/2.998970812 | agree |
| 10,000,000 | 294 ms · 99,323,282 comparisons · 1.000096043/2.000478227/2.999060397 | 2,483 ms · 519,333,984 comparisons · 1.000096043/2.000478227/2.999060397 | 565 ms · 232,534,967 comparisons · 1.000096043/2.000478227/2.999060397 | agree |

## Membership pre-check: Bloom filter vs exact index only

n record keys are loaded; n more are offered, half of them new (as when a new data release arrives). A 'definitely new' from the Bloom filter (1% false positives) skips the exact look-up; 'maybe' is always confirmed. Against an in-memory HashSet the pre-check only adds time; against a sorted index searched by bisection (like an on-disk index) it saves look-ups. Work = exact look-ups performed.

| keys | HashSet only | Bloom + HashSet | Sorted index only | Bloom + sorted index | answers |
|---|---|---|---|---|---|
| 100,000 | 1.440 ms · 100,000 exact look-ups · 50249 | 4.619 ms · 50,740 exact look-ups · 50249 | 9.306 ms · 100,000 exact look-ups · 50249 | 11 ms · 50,740 exact look-ups · 50249 | agree |
| 1,000,000 | 31 ms · 1,000,000 exact look-ups · 500576 | 109 ms · 505,621 exact look-ups · 500576 | 119 ms · 1,000,000 exact look-ups · 500576 | 129 ms · 505,621 exact look-ups · 500576 | agree |
| 5,000,000 | 186 ms · 5,000,000 exact look-ups · 2501389 | 664 ms · 2,526,486 exact look-ups · 2501389 | 935 ms · 5,000,000 exact look-ups · 2501389 | 927 ms · 2,526,486 exact look-ups · 2501389 | agree |

## Record linkage: max-flow matching vs greedy

Random bipartite graphs shaped like linking rosters (n left, n right, 5 candidate edges each, half aimed at a popular quarter). Maximum matching (O(V·E)) links as many pairs as possible; greedy (best score first) is faster but links fewer. Answer = pairs linked (they differ: that is the point).

| vertices per side | Max-flow matching | Greedy | answers |
|---|---|---|---|
| 500 | 3.281 ms · 1,458,991 edge scans · 472 pairs | 0.975 ms · 2,483 edge scans · 390 pairs | not comparable (optimal vs greedy) |
| 2,000 | 39 ms · 23,073,552 edge scans · 1857 pairs | 1.815 ms · 9,984 edge scans · 1547 pairs | not comparable (optimal vs greedy) |
| 8,000 | 684 ms · 366,776,585 edge scans · 7418 pairs | 5.531 ms · 39,984 edge scans · 6254 pairs | not comparable (optimal vs greedy) |

## Squad planner: bottleneck matching vs greedy

n departures, each with its 20 best candidates among 10·n players (popular players are near the top for several roles). Bottleneck matching fills every role and maximises the weakest similarity: O(log E) max-flow matchings. Greedy takes the best pair first. Answer = the weakest similarity (and roles left empty).

| departures | Bottleneck matching | Greedy | answers |
|---|---|---|---|
| 8 | 0.201 ms · 1,943 edge scans · weakest 80.5, 0 empty | 0.011 ms · 140 edge scans · weakest 80.5, 0 empty | not comparable (optimal vs greedy) |
| 32 | 0.458 ms · 34,414 edge scans · weakest 85.6, 0 empty | 0.037 ms · 612 edge scans · weakest 82.1, 0 empty | not comparable (optimal vs greedy) |
| 128 | 2.190 ms · 620,949 edge scans · weakest 79.3, 0 empty | 0.230 ms · 2,527 edge scans · weakest 78.2, 0 empty | not comparable (optimal vs greedy) |

