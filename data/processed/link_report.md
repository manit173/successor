# Linking report

## Rows read

| Source | Seasons | Rows |
| --- | --- | --- |
| Transfermarkt (appearances; lineups + events where a league has none) | 2012/13–2025/26 | 97579 |
| Understat | 2014/15–2023/24 | 27761 |
| FBref archive (per 90 → totals; leagues/seasons not covered below) | 2017/18–2024/25 | 16005 |
| FBref (StatsBomb era, per 90 → totals) | 2021/22 | 2921 |
| FBref (full) | 2022/23 | 2889 |
| FBref (full) | 2023/24 | 4379 |
| FBref (full) | 2024/25 | 2854 |
| FBref (basic) | 2025/26 | 2839 |
| Understat + Sofascore (Big 5) | 2025/26 | 2839 |

**Duplicates (Bloom pre-check, exact confirmation):** 160,066 keys checked; 159,614 proved new by the Bloom filter with no exact look-up; 148 duplicate rows dropped; 304 Bloom false positives caught by the exact check (filter: 1,534,242 bits, k = 7, 187.3 KiB).

## Linking to Transfermarkt players

| Source | Rows | Linked | Share |
| --- | --- | --- | --- |
| UNDERSTAT | 27,761 | 27,582 | 99.4% of rows, 99.8% of minutes |
| FBREF | 28,900 | 28,351 | 98.1% of rows, 99.1% of minutes |
| FBREF_BASIC | 2,839 | 2,827 | 99.6% of rows, 99.8% of minutes |
| SOFASCORE | 2,839 | 2,827 | 99.6% of rows, 99.8% of minutes |

Clubs linked: 2,464 league-season clubs; 0 by elimination (the last unmatched pair in a league-season); 0 unmatched. Matching work: 3,268,034 edge scans. 1 source ids linked to different players in different seasons (kept the majority; 349 rows changed).

FBref archive rows not used: 212 without a linked Transfermarkt club-season, 757 whose minutes differ from Transfermarkt's at that club by more than 150 (a mid-season transfer or two namesakes added together, or a wrong link). Used / dropped by league: Belgian Pro League 2011/105, Bundesliga 1161/40, Eredivisie 2350/73, La Liga 1203/72, Ligue 1 1048/54, Premier League 1209/39, Primeira Liga 2048/103, Serie A 1262/72, Süper Lig 2596/199.

## Checks

- Player-seasons with more minutes in one league than its season holds (the most games any club played × 90 + stoppage): **0** (a sign of two players merged).
- Linked spellings that look unlike the player's name: **82** (Jonny Otto ← Jonny Castro; Raúl Carnero ← Raúl García; Miguel Muñoz ← Miguel Ángel; Lazare Amani ← Jean Thierry Lazare; Arnaut Danjuma ← Arnaut Groeneveld; Igor Silva ← Igor Carioca; Kelvin Amian ← Kelvin Adou; Raphael Rossi ← Raphael Branco; Amath Ndiaye ← Amath Diedhiou; Joel Tagueu ← Diederrick Joel; Mamadou Loum ← Loum Ndiaye; Kasim Adams ← Kasim Nuhu; Nego Tembeng ← Azongha Tembeng Abenego; Marvin Martins ← Marvin da Graca; Pedro Augusto ← Pedro Borges; Victor Sá ← Joao Victor; Tanguy Nianzou ← Nianzou Kouassi; Percy Prado ← Percy Ruiz; Nico Melamed ← Nico Ribaudo; Marcos André ← Marcos de Sousa).

## Result

29,307 players, 98,456 player-seasons (one per player, club and season).

## Projection calibration

Fitted on target seasons [2017, 2018, 2019]: R = 1500 minutes, range scale 1.1, method MARCEL.
- NAIVE: MAE 0.749 on [2020, 2021, 2022, 2023] (+0.0% vs naive), 80% range coverage 72.0%
- MEAN3: MAE 0.690 on [2020, 2021, 2022, 2023] (+8.0% vs naive), 80% range coverage 76.2%
- MARCEL: MAE 0.638 on [2020, 2021, 2022, 2023] (+14.8% vs naive), 80% range coverage 79.5%
- COMPARABLES: MAE 0.663 on [2020, 2021, 2022, 2023] (+11.6% vs naive), 80% range coverage 77.3%
- BLEND: MAE 0.644 on [2020, 2021, 2022, 2023] (+14.0% vs naive), 80% range coverage 78.8%

## League strength

Backtest with factors from transfers into the Big 5 up to 2019/20 (MARCEL): all test players MAE 0.638 (without factors 0.641); 268 arrivals from other leagues MAE 0.680 (without factors 0.738).

Factors used by the app (every transfer in the data; raw = before shrinkage, movers = transfers with the stat measured):

| League | Goals (np) | Assists | xG | Key passes | Prog passes | Tackles | Interceptions | Blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Eredivisie | 0.74 (raw 0.69, n 102) | 0.64 (raw 0.63, n 158) | 0.71 (raw 0.69, n 94) | 0.76 (raw 0.75, n 94) | 0.72 (raw 0.70, n 77) | 1.03 (raw 1.02, n 89) | 1.07 (raw 1.14, n 96) | 1.21 (raw 1.20, n 89) |
| Primeira Liga | 0.78 (raw 0.85, n 59) | 0.70 (raw 0.78, n 142) | 0.84 (raw 0.86, n 50) | 0.86 (raw 0.90, n 50) | 0.83 (raw 0.86, n 46) | 1.06 (raw 1.07, n 46) | 1.00 (raw 0.97, n 52) | 1.22 (raw 1.22, n 46) |
| Belgian Pro League | 0.77 (raw 0.79, n 95) | 0.70 (raw 0.74, n 159) | 0.83 (raw 0.85, n 95) | 0.80 (raw 0.81, n 95) | 0.77 (raw 0.77, n 85) | 1.03 (raw 1.03, n 85) | 1.00 (raw 0.97, n 85) | 1.23 (raw 1.25, n 85) |
| Süper Lig | 0.77 (raw 0.86, n 35) | 0.67 (raw 0.71, n 86) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.05 (raw 1.08, n 29) | 1.22 (prior) |
| Scottish Premiership | 0.76 (prior) | 0.63 (raw 0.50, n 29) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Greek Super League | 0.76 (prior) | 0.64 (raw 0.57, n 36) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Danish Superliga | 0.76 (prior) | 0.62 (raw 0.52, n 55) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Ukrainian Premier League | 0.76 (prior) | 0.64 (raw 0.58, n 32) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Austrian Bundesliga | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Swiss Super League | 0.76 (prior) | 0.66 (raw 0.80, n 7) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Czech First League | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Croatian HNL | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Ekstraklasa | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Romanian SuperLiga | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Serbian SuperLiga | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Eliteserien | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |
| Allsvenskan | 0.76 (prior) | 0.66 (prior) | 0.78 (prior) | 0.80 (prior) | 0.77 (prior) | 1.04 (prior) | 1.03 (prior) | 1.22 (prior) |

Built in 13.6 s.
