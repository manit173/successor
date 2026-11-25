package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Source;

/**
 * One player's season at one club as a single source reports it, before linking.
 *
 * @param key          the player's identity in that source (Transfermarkt / Understat id, or "name|birth year")
 * @param clubKey      the club's identity in that source (Transfermarkt club id, or the club name)
 * @param birthYear    0 when the source does not say
 * @param positionCode the source's position label (Understat "AMC", FBref "MF,FW", …)
 * @param stats        season totals indexed by {@code Stat.ordinal()}, NaN when not measured
 */
public record SourceRow(Source source, String key, String name, int birthYear, int season, League league,
                        String clubKey, String club, String positionCode, double[] stats) {
}
