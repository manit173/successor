# Successor

A native macOS app for football scouting. It compares players within their position and role, projects their next
season, and finds who could replace a player at his peak. Advanced Algorithms · CIA 3.

**Architecture:** a Java engine (the assessed algorithms, plain Java), a Spring Boot REST API over it, and a native
macOS client (JavaFX, Cupertino theme) that starts the API on a loopback port and talks to it over HTTP.

| Algorithm (syllabus unit) | Job in the app | Baseline |
| --- | --- | --- |
| Bipartite matching via max-flow, Edmonds–Karp (U5) | Linking clubs and players across sources; squad planner | Greedy assignment |
| Bloom filter (U6, CO5) | Duplicate pre-check while loading, confirmed exactly | HashSet |
| Trie (U4) | Player search ignoring accents and case | Linear scan |
| Randomized quickselect (U6, CO5) | Percentiles within a position; top-k similar players | Sort; median-of-medians; heap |

## Setup

Needs JDK 25+ and Maven (`brew install openjdk maven`).

```bash
scripts/run.sh                 # build and open the app (uses data/processed if built, else the synthetic sample)
scripts/build_data.sh          # download the real sources and build data/processed (≈ 250 MB download, ~6 s build)
scripts/package-mac.sh         # dist/Successor.app + dist/Successor-1.0.0.dmg (own Java runtime, AOT class cache)
mvn install                    # build and run all tests
mvn -pl server spring-boot:run # API alone on http://127.0.0.1:8080
curl -s http://127.0.0.1:8080/api/meta
java -cp engine/target/classes com.successor.engine.bench.BenchmarkCli docs/benchmarks full   # benchmarks
```

## Data

Real data comes from Transfermarkt (CC0), Understat (2014/15–2023/24) and FBref (2024/25 full, 2025/26 basic). Sources,
licences and limitations are in [`data/SOURCES.md`](data/SOURCES.md). Raw and processed real data are not committed.
`data/sample/` is a committed synthetic dataset (made-up players, every stat present) so the app runs from a fresh
checkout. Each build writes `data/processed/link_report.md`, with link rates and consistency checks.

## Layout

```
engine/   algo/ (assessed algorithms, java.* only) · data/ (readers, linking, dataset) · model/
server/   Spring Boot REST API (validation, RFC 9457 error documents)
app/      native macOS client
scripts/  run.sh · fetch_data.sh · build_data.sh · fetch_understat.py
data/     SOURCES.md · sample/ (synthetic)
```
