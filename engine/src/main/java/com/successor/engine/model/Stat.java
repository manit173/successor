package com.successor.engine.model;

/**
 * Every statistic the dataset can hold for a player-season. Values are season totals; {@code per90} stats are compared
 * per 90 minutes. A missing value (the source did not measure it) is stored as NaN and never treated as 0.
 */
public enum Stat {
    MINUTES("minutes", "Minutes", Group.PLAYING_TIME, false),
    GAMES("games", "Games", Group.PLAYING_TIME, false),
    STARTS("starts", "Starts", Group.PLAYING_TIME, false),
    GOALS("goals", "Goals", Group.SHOOTING, true),
    NP_GOALS("np_goals", "Non-penalty goals", Group.SHOOTING, true),
    ASSISTS("assists", "Assists", Group.CREATION, true),
    SHOTS("shots", "Shots", Group.SHOOTING, true),
    SHOTS_ON_TARGET("shots_on_target", "Shots on target", Group.SHOOTING, true),
    XG("xg", "xG", Group.SHOOTING, true),
    NPXG("npxg", "Non-penalty xG", Group.SHOOTING, true),
    XA("xa", "xA (expected assists)", Group.CREATION, true),
    KEY_PASSES("key_passes", "Key passes", Group.CREATION, true),
    XG_CHAIN("xg_chain", "xGChain", Group.CREATION, true),
    XG_BUILDUP("xg_buildup", "xGBuildup", Group.PROGRESSION, true),
    SCA("sca", "Shot-creating actions", Group.CREATION, true),
    GCA("gca", "Goal-creating actions", Group.CREATION, true),
    PROG_PASSES("prog_passes", "Progressive passes", Group.PROGRESSION, true),
    PROG_CARRIES("prog_carries", "Progressive carries", Group.PROGRESSION, true),
    PROG_RECEPTIONS("prog_receptions", "Progressive passes received", Group.PROGRESSION, true),
    PASSES_FINAL_THIRD("passes_final_third", "Passes into final third", Group.PROGRESSION, true),
    PASSES_INTO_BOX("passes_into_box", "Passes into the box", Group.CREATION, true),
    CROSSES_INTO_BOX("crosses_into_box", "Crosses into the box", Group.CREATION, true),
    CARRIES_INTO_BOX("carries_into_box", "Carries into the box", Group.PROGRESSION, true),
    PASSES_COMPLETED("passes_completed", "Passes completed", Group.PROGRESSION, true),
    PASSES_ATTEMPTED("passes_attempted", "Passes attempted", Group.PROGRESSION, true),
    TOUCHES("touches", "Touches", Group.PROGRESSION, true),
    TOUCHES_ATT_BOX("touches_att_box", "Touches in the opposition box", Group.SHOOTING, true),
    TAKE_ONS("take_ons", "Successful take-ons", Group.PROGRESSION, true),
    CROSSES("crosses", "Crosses", Group.CREATION, true),
    FOULS_DRAWN("fouls_drawn", "Fouls drawn", Group.PROGRESSION, true),
    TACKLES("tackles", "Tackles", Group.DEFENDING, true),
    TACKLES_WON("tackles_won", "Tackles won", Group.DEFENDING, true),
    INTERCEPTIONS("interceptions", "Interceptions", Group.DEFENDING, true),
    BLOCKS("blocks", "Blocks", Group.DEFENDING, true),
    CLEARANCES("clearances", "Clearances", Group.DEFENDING, true),
    RECOVERIES("recoveries", "Ball recoveries", Group.DEFENDING, true),
    AERIALS_WON("aerials_won", "Aerials won", Group.DEFENDING, true),
    DISPOSSESSED("dispossessed", "Dispossessed", Group.PROGRESSION, true),
    MISCONTROLS("miscontrols", "Miscontrols", Group.PROGRESSION, true),
    YELLOW_CARDS("yellow_cards", "Yellow cards", Group.DISCIPLINE, true),
    RED_CARDS("red_cards", "Red cards", Group.DISCIPLINE, true),
    GOALS_AGAINST("goals_against", "Goals against", Group.GOALKEEPING, true),
    SHOTS_ON_TARGET_AGAINST("shots_on_target_against", "Shots on target faced", Group.GOALKEEPING, true),
    SAVES("saves", "Saves", Group.GOALKEEPING, true),
    CLEAN_SHEETS("clean_sheets", "Clean sheets", Group.GOALKEEPING, false),
    PSXG("psxg", "Post-shot xG faced", Group.GOALKEEPING, true);

    public enum Group {
        PLAYING_TIME, SHOOTING, CREATION, PROGRESSION, DEFENDING, GOALKEEPING, DISCIPLINE
    }

    public static final int COUNT = values().length;

    private final String key;
    private final String label;
    private final Group group;
    private final boolean per90;

    Stat(String key, String label, Group group, boolean per90) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.per90 = per90;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public Group group() {
        return group;
    }

    public boolean per90() {
        return per90;
    }

    public static Stat fromKey(String key) {
        for (Stat s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
