#!/usr/bin/env python3
"""Fetch per-season player stats for the top-5 leagues from understat.com.

Uses the same JSON endpoint as the open-source `soccerdata` library
(https://github.com/probberechts/soccerdata): GET /getLeagueData/{league}/{season}.
Standard library only. Polite by design: one request at a time, a pause between requests, and every
response cached in data/raw/understat/ so re-running never downloads the same season twice.

Understat data is used for non-commercial, academic purposes with attribution (see data/SOURCES.md).
Note: on 23 and 25 Nov 2026 understat.com served only a "We will come back soon" page, so the project uses the
Kaggle mirror downloaded by scripts/fetch_data.sh. This script is kept for when the site returns.

Usage: python3 scripts/fetch_understat.py [--first 2014] [--last 2026] [--delay 3]
Output: data/processed/understat_player_seasons.csv
"""
import argparse
import csv
import http.cookiejar
import json
import sys
import time
import urllib.request
from pathlib import Path

BASE = "https://understat.com"
LEAGUES = ["EPL", "La_liga", "Bundesliga", "Serie_A", "Ligue_1"]
ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw" / "understat"
OUT = ROOT / "data" / "processed" / "understat_player_seasons.csv"
UA = "Mozilla/5.0 (Macintosh) successor-scouting-student-project/0.1 (non-commercial, academic)"

FIELDS = ["league", "season", "player_id", "player", "team", "teams", "position", "games", "minutes",
          "goals", "npg", "xg", "npxg", "assists", "xa", "shots", "key_passes", "xg_chain", "xg_buildup",
          "yellow_cards", "red_cards"]


def opener():
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    op.addheaders = [("User-Agent", UA)]
    op.open(BASE, timeout=30).read()  # session cookie, as soccerdata does
    return op


def fetch(op, league, season, delay):
    cache = RAW / f"{league}_{season}.json"
    if cache.exists() and cache.stat().st_size > 0:
        return json.loads(cache.read_text(encoding="utf-8")), True
    req = urllib.request.Request(f"{BASE}/getLeagueData/{league}/{season}",
                                 headers={"X-Requested-With": "XMLHttpRequest",
                                          "Referer": f"{BASE}/league/{league}/{season}"})
    for attempt in range(3):
        try:
            body = op.open(req, timeout=60).read()
            data = json.loads(body)
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_bytes(body)
            time.sleep(delay)
            return data, False
        except Exception as e:  # network hiccup or rate limit: back off and retry
            wait = delay * (attempt + 2)
            print(f"  {league} {season}: {e} (retrying in {wait}s)", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError(f"giving up on {league} {season}")


def rows(league, season, data):
    for p in data.get("players", []):
        teams = p["team_title"]
        yield {
            "league": league, "season": season, "player_id": p["id"], "player": p["player_name"],
            "team": teams.split(",")[0], "teams": teams, "position": p["position"],
            "games": p["games"], "minutes": p["time"], "goals": p["goals"], "npg": p["npg"],
            "xg": p["xG"], "npxg": p["npxG"], "assists": p["assists"], "xa": p["xA"], "shots": p["shots"],
            "key_passes": p["key_passes"], "xg_chain": p["xGChain"], "xg_buildup": p["xGBuildup"],
            "yellow_cards": p["yellow_cards"], "red_cards": p["red_cards"],
        }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--first", type=int, default=2014, help="first season start year (Understat starts 2014)")
    ap.add_argument("--last", type=int, default=2026, help="last season start year (2026 = 2026/27, partial)")
    ap.add_argument("--delay", type=float, default=3.0, help="seconds between requests")
    a = ap.parse_args()

    op = opener()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    with OUT.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        for season in range(a.first, a.last + 1):
            for league in LEAGUES:
                data, cached = fetch(op, league, season, a.delay)
                n = 0
                for r in rows(league, season, data):
                    w.writerow(r)
                    n += 1
                total += n
                print(f"{league:<10} {season}/{(season + 1) % 100:02d}  {n:4d} players{'  (cached)' if cached else ''}")
    print(f"wrote {total} player-season rows to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
