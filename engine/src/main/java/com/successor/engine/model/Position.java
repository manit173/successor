package com.successor.engine.model;

/**
 * Positions used for comparison cohorts. Players are only ranked and compared against players in the same position
 * (and, within it, the same role).
 */
public enum Position {
    GK("Goalkeeper"),
    CB("Centre-back"),
    FB("Full-back"),
    DM("Defensive midfielder"),
    CM("Central midfielder"),
    AM("Attacking midfielder"),
    W("Winger"),
    ST("Striker");

    private final String label;

    Position(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Transfermarkt {@code sub_position}, e.g. "Attacking Midfield". */
    public static Position fromTransfermarkt(String sub, String position) {
        if (sub != null) {
            switch (sub) {
                case "Goalkeeper" -> {
                    return GK;
                }
                case "Centre-Back" -> {
                    return CB;
                }
                case "Left-Back", "Right-Back" -> {
                    return FB;
                }
                case "Defensive Midfield" -> {
                    return DM;
                }
                case "Central Midfield", "Left Midfield", "Right Midfield" -> {
                    return CM;
                }
                case "Attacking Midfield" -> {
                    return AM;
                }
                case "Left Winger", "Right Winger" -> {
                    return W;
                }
                case "Centre-Forward", "Second Striker" -> {
                    return ST;
                }
                default -> {
                }
            }
        }
        if (position == null) {
            return null;
        }
        return switch (position) {
            case "Goalkeeper" -> GK;
            case "Defender" -> CB;
            case "Midfield" -> CM;
            case "Attack" -> ST;
            default -> null;
        };
    }

    /**
     * Understat's per-season position code (the role played most often that season), e.g. "AMC", "DL", "FWR".
     * Returns null for "Sub" (mostly came off the bench) so the caller can fall back to the player's listed position.
     */
    public static Position fromUnderstat(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        if (c.isEmpty() || c.equals("Sub")) {
            return null;
        }
        if (c.equals("GK")) {
            return GK;
        }
        if (c.equals("DC")) {
            return CB;
        }
        if (c.equals("DL") || c.equals("DR") || c.equals("DML") || c.equals("DMR")) {
            return FB;
        }
        if (c.equals("DMC")) {
            return DM;
        }
        if (c.equals("MC") || c.equals("ML") || c.equals("MR")) {
            return CM;
        }
        if (c.equals("AMC")) {
            return AM;
        }
        if (c.equals("AML") || c.equals("AMR") || c.equals("FWL") || c.equals("FWR")) {
            return W;
        }
        if (c.startsWith("FW")) {
            return ST;
        }
        return null;
    }
}
