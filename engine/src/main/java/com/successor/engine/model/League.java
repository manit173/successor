package com.successor.engine.model;

import java.util.Arrays;
import java.util.List;

/**
 * European top divisions in the dataset. Each source spells them differently. The Big 5 are the yardstick: cohorts
 * (percentiles, robust z-scores) are built from Big-5 players only, so a player from any league is described as
 * "where he would rank among Big-5 players at his position". Stats from other leagues are not adjusted for league
 * strength.
 */
public enum League {
    PREMIER_LEAGUE("Premier League", "England", true, "GB1", "EPL", "eng Premier League"),
    LA_LIGA("La Liga", "Spain", true, "ES1", "La liga", "es La Liga"),
    BUNDESLIGA("Bundesliga", "Germany", true, "L1", "Bundesliga", "de Bundesliga"),
    SERIE_A("Serie A", "Italy", true, "IT1", "Serie A", "it Serie A"),
    LIGUE_1("Ligue 1", "France", true, "FR1", "Ligue 1", "fr Ligue 1"),
    EREDIVISIE("Eredivisie", "Netherlands", false, "NL1", null, "nl Eredivisie"),
    PRIMEIRA_LIGA("Primeira Liga", "Portugal", false, "PO1", null, "pt Primeira Liga", "Liga Portugal"),
    BELGIAN_PRO_LEAGUE("Belgian Pro League", "Belgium", false, "BE1", null, "be Belgian Pro League", "Pro League",
            "Jupiler Pro League"),
    SUPER_LIG("Süper Lig", "Türkiye", false, "TR1", null, "tr Süper Lig", "Super Lig"),
    SCOTTISH_PREMIERSHIP("Scottish Premiership", "Scotland", false, "SC1", null, "sco Scottish Premiership",
            "Premiership"),
    GREEK_SUPER_LEAGUE("Greek Super League", "Greece", false, "GR1", null, "gr Super League"),
    DANISH_SUPERLIGA("Danish Superliga", "Denmark", false, "DK1", null, "dk Superliga"),
    UKRAINIAN_PREMIER_LEAGUE("Ukrainian Premier League", "Ukraine", false, "UKR1", null, "ua Premier League"),
    // The leagues below have no appearance rows in the Transfermarkt snapshot; their 2024/25 and 2025/26 player
    // seasons are rebuilt from match lineups and events (see TransfermarktReader).
    AUSTRIAN_BUNDESLIGA("Austrian Bundesliga", "Austria", false, "A1", null, "at Bundesliga"),
    SWISS_SUPER_LEAGUE("Swiss Super League", "Switzerland", false, "C1", null, "ch Super League"),
    CZECH_FIRST_LEAGUE("Czech First League", "Czechia", false, "TS1", null, "cz First League"),
    CROATIAN_HNL("Croatian HNL", "Croatia", false, "KR1", null, "hr HNL"),
    EKSTRAKLASA("Ekstraklasa", "Poland", false, "PL1", null, "pl Ekstraklasa"),
    ROMANIAN_SUPERLIGA("Romanian SuperLiga", "Romania", false, "RO1", null, "ro Liga I"),
    SERBIAN_SUPERLIGA("Serbian SuperLiga", "Serbia", false, "SER1", null, "rs SuperLiga"),
    ELITESERIEN("Eliteserien", "Norway", false, "NO1", null, "no Eliteserien"),
    ALLSVENSKAN("Allsvenskan", "Sweden", false, "SE1", null, "se Allsvenskan");

    private final String label;
    private final String country;
    private final boolean big5;
    private final String transfermarkt;
    private final String understat;
    private final String fbref;
    private final List<String> aliases;

    League(String label, String country, boolean big5, String transfermarkt, String understat, String fbref,
           String... aliases) {
        this.label = label;
        this.country = country;
        this.big5 = big5;
        this.transfermarkt = transfermarkt;
        this.understat = understat;
        this.fbref = fbref;
        this.aliases = List.of(aliases);
    }

    public String label() {
        return label;
    }

    public String country() {
        return country;
    }

    /** One of England, Spain, Germany, Italy and France's top divisions: the comparison yardstick. */
    public boolean big5() {
        return big5;
    }

    public static List<League> bigFive() {
        return Arrays.stream(values()).filter(League::big5).toList();
    }

    public static League fromTransfermarkt(String competitionId) {
        for (League l : values()) {
            if (l.transfermarkt.equals(competitionId)) {
                return l;
            }
        }
        return null;
    }

    public static League fromUnderstat(String name) {
        for (League l : values()) {
            if (name != null && name.equals(l.understat)) {
                return l;
            }
        }
        return null;
    }

    public static League fromFbref(String comp) {
        for (League l : values()) {
            if (l.fbref.equals(comp)) {
                return l;
            }
        }
        return null;
    }

    /** Matches the display label, the enum name, or a known alternative spelling ("Pro League" → Belgium). */
    public static League fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String t = label.trim();
        for (League l : values()) {
            if (l.label.equalsIgnoreCase(t) || l.name().equalsIgnoreCase(t)) {
                return l;
            }
        }
        for (League l : values()) {
            for (String a : l.aliases) {
                if (a.equalsIgnoreCase(t)) {
                    return l;
                }
            }
        }
        return null;
    }
}
