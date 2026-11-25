#!/bin/zsh
# Downloads every raw data source into data/raw/ (not committed: see data/SOURCES.md for licences) and checks
# that each file is the exact snapshot the project was built and benchmarked on.
#
# Usage: scripts/fetch_data.sh
set -euo pipefail
cd "$(dirname "$0")/.."
RAW=data/raw
mkdir -p "$RAW"

typeset -A URLS SHA
URLS[transfermarkt-datasets.zip]=https://pub-e682421888d945d684bcae8890b0ec20.r2.dev/data/transfermarkt-datasets.zip
SHA[transfermarkt-datasets.zip]=ae2270f7820e09329c423a12562f4494e237d0dc1a4624cff21df050e79d5a18
URLS[kaggle-understat-data.zip]=https://www.kaggle.com/api/v1/datasets/download/codytipton/understat-data
SHA[kaggle-understat-data.zip]=06d80091a799ea693a50992770b007d1ef71cdfdb66c4aaa1ce43cf116a6c710
URLS[kaggle-football-players-stats-2024-2025.zip]=https://www.kaggle.com/api/v1/datasets/download/hubertsidorowicz/football-players-stats-2024-2025
SHA[kaggle-football-players-stats-2024-2025.zip]=fcddc924256d6d4a09f0f1a519f4ea7a28f5c70edb6bb9e4f83fde3cda91ba6c
URLS[kaggle-football-players-stats-2025-2026.zip]=https://www.kaggle.com/api/v1/datasets/download/hubertsidorowicz/football-players-stats-2025-2026
SHA[kaggle-football-players-stats-2025-2026.zip]=5b0b6064df3b30ae6b26df89f8d3c94c4b621e710ac4be2a550122ad052e97f1

URLS[kaggle-fbref-2021-22.zip]=https://www.kaggle.com/api/v1/datasets/download/vivovinco/20212022-football-player-stats
SHA[kaggle-fbref-2021-22.zip]=30b643e0d4942b8425c426a33052ec9a21a97cadc479c1cadd0973e188a1d5ec
URLS[kaggle-fbref-2022-23.zip]=https://www.kaggle.com/api/v1/datasets/download/ameyaranade/big-5-european-leagues-player-stats-2022-23
SHA[kaggle-fbref-2022-23.zip]=02b726290bcfce4935fa7da904a5a4e9fa9e9ad500e01596afec97cc8c3a910c
URLS[kaggle-fbref-2023-24.zip]=https://www.kaggle.com/api/v1/datasets/download/anisguechtouli/football-leagues-data-2023-2024
SHA[kaggle-fbref-2023-24.zip]=e12e051372993e630db254e5eed3c0bf2daab389a00dceba6ca4089f7206d004
URLS[kaggle-fbref-archive.zip]=https://www.kaggle.com/api/v1/datasets/download/jacksonjohannessen/fifa-and-irl-soccer-player-data
SHA[kaggle-fbref-archive.zip]=3a3c0a15eb62a5fb9fa70f930329785a24712a880bd7b4faccc5e64175afae0f
URLS[kaggle-big5-2526.zip]=https://www.kaggle.com/api/v1/datasets/download/chuongtrinh/top-5-league-individual-stats-season-20252026
SHA[kaggle-big5-2526.zip]=f833ee0001c55b4e50d81b84edce22845ec964d6cefae3f65cb46f469a821258

typeset -A DIRS
DIRS[transfermarkt-datasets.zip]=transfermarkt
DIRS[kaggle-understat-data.zip]=kaggle-understat
DIRS[kaggle-football-players-stats-2024-2025.zip]=kaggle-fbref-2425
DIRS[kaggle-football-players-stats-2025-2026.zip]=kaggle-fbref-2526
DIRS[kaggle-fbref-2021-22.zip]=kaggle-fbref-2122
DIRS[kaggle-fbref-2022-23.zip]=kaggle-fbref-2223
DIRS[kaggle-fbref-2023-24.zip]=kaggle-fbref-2324
DIRS[kaggle-fbref-archive.zip]=kaggle-fbref-archive
DIRS[kaggle-big5-2526.zip]=kaggle-big5-2526

for f in ${(k)URLS}; do
  if [[ ! -s "$RAW/$f" ]]; then
    echo "downloading $f"
    curl -fL --retry 3 -o "$RAW/$f" "${URLS[$f]}"
  fi
  actual=$(shasum -a 256 "$RAW/$f" | cut -d' ' -f1)
  if [[ "$actual" != "${SHA[$f]}" ]]; then
    echo "warning: $f differs from the snapshot in data/SOURCES.md (the source was updated); results may differ"
  fi
  rm -rf "$RAW/${DIRS[$f]}" && mkdir -p "$RAW/${DIRS[$f]}"
  unzip -oq "$RAW/$f" -d "$RAW/${DIRS[$f]}"
  echo "ok  $f -> $RAW/${DIRS[$f]}/"
done
