package com.successor.engine.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A player after linking the sources.
 *
 * @param id            stable id: {@code tm:<Transfermarkt id>} when linked to Transfermarkt, otherwise
 *                      {@code us:<Understat id>} or {@code fb:<name>:<birth year>}
 * @param name          display name (Transfermarkt's when linked)
 * @param aliases       other spellings seen in the sources (e.g. "Mathis Cherki" for Rayan Cherki)
 * @param birthDate     null when only a birth year is known
 * @param birthYear     0 when unknown
 * @param position      listed position (seasons may record a different one)
 * @param subPosition   the source's own label, e.g. "Attacking Midfield"
 * @param marketValueEur latest Transfermarkt value, 0 when unknown
 */
public record Player(String id, String name, List<String> aliases, LocalDate birthDate, int birthYear, String nation,
                     Position position, String subPosition, String foot, int heightCm, long marketValueEur) {

    public Player {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (birthYear == 0 && birthDate != null) {
            birthYear = birthDate.getYear();
        }
    }

    /** Age on 1 July of the season's first year (the usual football-season cutoff); −1 if unknown. */
    public int ageAtSeason(int seasonStartYear) {
        if (birthDate != null) {
            LocalDate ref = LocalDate.of(seasonStartYear, 7, 1);
            return java.time.Period.between(birthDate, ref).getYears();
        }
        return birthYear > 0 ? seasonStartYear - birthYear : -1;
    }
}
