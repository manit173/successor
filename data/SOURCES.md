# Data sources

Raw files live in `data/raw/` and are **not committed**; `scripts/fetch_data.sh` downloads them and checks the
SHA-256 of each snapshot. All use here is non-commercial and academic, with attribution.

| Source | What we use | Seasons | Licence / terms | Snapshot (SHA-256) |
| --- | --- | --- | --- | --- |
| **Transfermarkt**, via [dcaribou/transfermarkt-datasets](https://github.com/dcaribou/transfermarkt-datasets) | Player identity, date of birth, detailed position (e.g. *Attacking Midfield*), market value; per-game minutes, goals, assists, cards. For leagues with no appearance rows (Austria, Switzerland, Czechia, Croatia, Poland, Romania, Serbia, Norway, Sweden) each game is rebuilt from the lineup and match events; checked on 191,352 player-games that have both: minutes within 0.10% in total, goals exact, assists 99.6% and cards 99.9% identical | to 2025/26 (frozen 6 Jul 2026) | **CC0 1.0** | `ae2270f7…` |
| **Understat** (understat.com), via the Kaggle mirror [codytipton/understat-data](https://www.kaggle.com/datasets/codytipton/understat-data) | Per-season xG, npxG, xA, shots, key passes, xGChain, xGBuildup, minutes, goals, assists; top 5 leagues (its Russian league is not used) | 2014/15–2023/24 complete; 2024/25 only the first weeks (scraped 30 Sep 2024) | Understat permits non-commercial use (Understat support, 2018, quoted by [understatr](https://ewenme.github.io/understatr)). The Kaggle mirror states no licence. | `06d80091…` |
| **FBref 2024/25** (Opta data), via Kaggle [hubertsidorowicz/football-players-stats-2024-2025](https://www.kaggle.com/datasets/hubertsidorowicz/football-players-stats-2024-2025) | Full role profiles: xG, xAG, key passes, progressive passes/carries/receptions, SCA, tackles, interceptions, recoveries, touches, take-ons, aerials… | 2024/25 | No licence stated. Data © Opta / Stats Perform, published by FBref, which lost the Opta licence on 20 Jan 2026. **Used locally only, never redistributed.** | `fcddc924…` |
| **FBref 2021/22** (StatsBomb-era FBref), via Kaggle [vivovinco/20212022-football-player-stats](https://www.kaggle.com/datasets/vivovinco/20212022-football-player-stats) | Progressive passes/carries/receptions, SCA, tackles, interceptions, recoveries, aerials, touches… (no xG columns) | 2021/22 | Kaggle licence CC BY 4.0; data from FBref (StatsBomb). Published **per 90**: converted to season totals with the minutes played. **Used locally only.** | `30b643e0…` |
| **FBref 2022/23** (Opta), via Kaggle [ameyaranade/big-5-european-leagues-player-stats-2022-23](https://www.kaggle.com/datasets/ameyaranade/big-5-european-leagues-player-stats-2022-23) | Full profile in nine tables, joined on player, club and birth year | 2022/23 | Kaggle licence CC0; data © Opta via FBref. **Used locally only.** | `02b72629…` |
| **FBref 2023/24** (Opta), via Kaggle [anisguechtouli/football-leagues-data-2023-2024](https://www.kaggle.com/datasets/anisguechtouli/football-leagues-data-2023-2024) | Full profile, normalised tables (17 leagues; the five are used). Birth year = 2023 − age (±1) | 2023/24 | No licence stated; data © Opta via FBref. **Used locally only.** | `e12e0513…` |
| **FBref 2025/26** (basic stats), via Kaggle [hubertsidorowicz/football-players-stats-2025-2026](https://www.kaggle.com/datasets/hubertsidorowicz/football-players-stats-2025-2026) | Minutes, goals, assists, shots, goalkeeping (no advanced stats after Jan 2026) | 2025/26 | As above | `5b0b6064…` |
| **FBref archive 2017/18–2024/25** (Opta / StatsBomb data), via Kaggle [jacksonjohannessen/fifa-and-irl-soccer-player-data](https://www.kaggle.com/datasets/jacksonjohannessen/fifa-and-irl-soccer-player-data) | Standard, shooting, passing, shot-creation, defensive and keeper tables, **per 90** (converted to totals), for the Big 5, Eredivisie, Primeira Liga, Belgian Pro League and Süper Lig. Only players also in the FIFA video game (mostly regulars). Used only for league-seasons no fuller export covers, and only rows that link to a Transfermarkt player and agree with his minutes at that club within 150 (measured: median difference 0, 99th percentile 151); this drops rows where the publisher added a mid-season transfer's two clubs, or two namesakes, together | 2017/18–2024/25 | Kaggle label MIT; data © Opta / StatsBomb via FBref. **Used locally only.** | `3a3c0a15…` |
| **Big 5 2025/26** (FBref + Understat + Sofascore), via Kaggle [chuongtrinh/top-5-league-individual-stats-season-20252026](https://www.kaggle.com/datasets/chuongtrinh/top-5-league-individual-stats-season-20252026) | Understat xG, npxG, xA, xGChain, xGBuildup, key passes; Sofascore tackles, blocks, clearances, recoveries, aerials won, dribbles, touches, passes, keeper saves | 2025/26 (full season) | Kaggle label MIT; data © Understat and Sofascore. **Used locally only.** | `f833ee00…` |
| `scripts/fetch_understat.py` | Direct Understat fetch, same endpoint as [soccerdata](https://github.com/probberechts/soccerdata) | — | — | Understat showed only a "We will come back soon" page on 23 and 25 Nov 2026, so the Kaggle mirror is used instead. |

## What this means for the app (documented limitation)

- **Attacking and creative stats: 2014/15–2025/26.** Understat covers the Big 5 to 2023/24, FBref 2024/25, and
  Understat again 2025/26.
- **Defensive and progression stats: 2017/18–2025/26 for the Big 5** (FBref archive 2017/18–2020/21, full FBref
  exports 2021/22–2024/25, Sofascore 2025/26). The archive covers about 70% of Big-5 players in its seasons, so it
  never replaces Understat's xG there: one season's cohort must not mix two xG models.
- Definitions differ between providers (StatsBomb-era FBref, Opta, Sofascore: e.g. what counts as a tackle). Each
  season is scaled by its own cohort, so a player is always compared with others measured the same way.
- Names differ across sources: Understat lists *Mathis Cherki*, while FBref and Transfermarkt use *Rayan Cherki*.
  Players are linked across sources by club-season rosters (see the linking step).

## League coverage

| Leagues | Player data | Advanced stats |
| --- | --- | --- |
| Premier League, La Liga, Bundesliga, Serie A, Ligue 1 (the **Big 5**) | 2012/13–2025/26 | 2014/15–2025/26 (defending and progression from 2017/18) |
| Eredivisie, Primeira Liga, Belgian Pro League | 2012/13–2025/26 | 2017/18–2024/25 (FBref archive and 2023/24 export; Primeira Liga and Belgium mostly to 2023/24) |
| Süper Lig | 2012/13–2025/26 | tackles and interceptions 2017/18–2024/25 |
| Scottish Premiership, Greek Super League, Danish Superliga, Ukrainian Premier League | 2012/13–2025/26 | none: minutes, goals, assists and cards only |
| Austria, Switzerland, Czechia, Croatia, Poland, Romania, Serbia, Norway, Sweden | 2024/25–2025/26, rebuilt from lineups and events | none |

The Russian Premier League is not used.

## League strength

- **The Big 5 are the yardstick.** Percentiles and robust z-scores are always computed against Big-5 players at the
  same position and season; aging curves and the backtest use Big-5 seasons.
- **Numbers from other leagues are converted to Big-5 level first**, by one factor per league and stat, measured from
  players who moved straight into the Big 5 (900+ minutes in both seasons), relative to players who moved between
  two Big-5 clubs. Where a league has few such moves, its factor is pulled toward the typical league's
  (DerSimonian–Laird random-effects shrinkage); leagues with almost none (Austria, Switzerland, …) get the typical
  league's factors. Examples (all transfers to 2025/26): Eredivisie goals ×0.74, assists ×0.64, xG ×0.71, tackles
  ×1.03, blocks ×1.21. Attacking output shrinks after such a move, defending does not, which is why there is no single
  factor per league. The full table is in `data/processed/link_report.md`.
- **Measured effect:** with factors estimated only from transfers up to 2019/20, the projection backtest
  (2020/21–2023/24) improves for the 268 players arriving from other leagues from MAE 0.738 to 0.680, and overall from
  0.641 to 0.638.
- Players whose recent seasons have only basic stats share too few role stats with most targets and are left out of
  successor searches (the app reports how many were skipped for this reason).
