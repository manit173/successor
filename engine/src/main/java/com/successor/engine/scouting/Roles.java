package com.successor.engine.scouting;

import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.successor.engine.model.Stat.*;

/**
 * Playing roles within each position, as transparent stat templates: a role is the set of stats that define it, with
 * weights. A player-season's fit for a role is the weighted mean of his percentiles on those stats (inverted for
 * stats where less is better). Templates follow common scouting vocabulary; they are a documented modelling choice,
 * not ground truth.
 */
public final class Roles {
    private Roles() {
    }

    /** A role template. Negative weights mean "lower is better" (e.g. goals conceded). */
    public record Role(String id, String name, Position position, String description, Map<Stat, Double> weights) {
    }

    private static final List<Role> ALL = new ArrayList<>();

    private static void role(String id, String name, Position p, String description, Object... statWeight) {
        Map<Stat, Double> w = new LinkedHashMap<>();
        for (int i = 0; i < statWeight.length; i += 2) {
            w.put((Stat) statWeight[i], ((Number) statWeight[i + 1]).doubleValue());
        }
        ALL.add(new Role(id, name, p, description, Collections.unmodifiableMap(w)));
    }

    static {
        role("shot-stopper", "Shot-stopper", Position.GK, "Saves shots and keeps goals out.",
                SAVES, 1, GOALS_AGAINST, -1, PSXG, 0.5);
        role("sweeper-keeper", "Sweeper keeper", Position.GK, "Starts attacks with the ball at his feet.",
                PASSES_ATTEMPTED, 1, PROG_PASSES, 0.8, XG_BUILDUP, 0.6);

        role("ball-playing-cb", "Ball-playing centre-back", Position.CB, "Breaks lines with passes and carries.",
                PROG_PASSES, 1, PASSES_COMPLETED, 0.8, PASSES_FINAL_THIRD, 0.8, XG_BUILDUP, 0.8, PROG_CARRIES, 0.5);
        role("stopper", "Stopper", Position.CB, "Wins the ball and the air; clears danger.",
                TACKLES, 0.8, INTERCEPTIONS, 1, CLEARANCES, 1, BLOCKS, 0.8, AERIALS_WON, 1);

        role("attacking-fb", "Attacking full-back", Position.FB, "Overlaps, crosses and creates chances.",
                CROSSES_INTO_BOX, 0.8, KEY_PASSES, 0.8, XA, 1, PROG_CARRIES, 0.8, PROG_RECEPTIONS, 0.8,
                TOUCHES_ATT_BOX, 0.6, XG_CHAIN, 0.6, ASSISTS, 0.4);
        role("defensive-fb", "Defensive full-back", Position.FB, "Defends the flank first.",
                TACKLES, 1, INTERCEPTIONS, 1, BLOCKS, 0.7, CLEARANCES, 0.7, AERIALS_WON, 0.5);
        role("inverted-fb", "Inverted full-back", Position.FB, "Steps into midfield to build play.",
                PASSES_COMPLETED, 1, PROG_PASSES, 1, XG_BUILDUP, 1, PASSES_FINAL_THIRD, 0.8);

        role("anchor", "Anchor / ball-winner", Position.DM, "Screens the defence and wins the ball back.",
                TACKLES, 1, INTERCEPTIONS, 1, RECOVERIES, 1, BLOCKS, 0.6, AERIALS_WON, 0.5, TACKLES_WON, 0.6);
        role("deep-playmaker", "Deep-lying playmaker", Position.DM, "Dictates play from deep.",
                PROG_PASSES, 1, PASSES_FINAL_THIRD, 1, PASSES_COMPLETED, 0.8, XG_BUILDUP, 1, XG_CHAIN, 0.5);

        role("box-to-box", "Box-to-box", Position.CM, "Covers both boxes: wins the ball, carries it, arrives late.",
                TACKLES, 0.8, RECOVERIES, 0.8, PROG_CARRIES, 1, TOUCHES_ATT_BOX, 0.8, NPXG, 0.7, XG_CHAIN, 0.7,
                INTERCEPTIONS, 0.5);
        role("cm-playmaker", "Deep-lying playmaker", Position.CM, "Builds play from the middle third.",
                PROG_PASSES, 1, PASSES_FINAL_THIRD, 1, XG_BUILDUP, 1, PASSES_COMPLETED, 0.7);
        role("advanced-eight", "Advanced eight", Position.CM, "A midfielder who creates in the final third.",
                KEY_PASSES, 1, XA, 1, SCA, 1, PASSES_INTO_BOX, 0.8, PROG_RECEPTIONS, 0.8, XG_CHAIN, 0.6, ASSISTS, 0.4);

        role("advanced-playmaker", "Advanced playmaker", Position.AM, "The creative hub: final balls and chances.",
                XA, 1, KEY_PASSES, 1, SCA, 1, PASSES_INTO_BOX, 0.8, PROG_PASSES, 0.6, XG_CHAIN, 0.8, GCA, 0.6, ASSISTS, 0.5);
        role("shadow-striker", "Shadow striker", Position.AM, "Arrives in the box to score.",
                NPXG, 1, SHOTS, 0.8, TOUCHES_ATT_BOX, 0.8, NP_GOALS, 0.8, PROG_RECEPTIONS, 0.6);

        role("inside-forward", "Inside forward", Position.W, "Cuts inside to dribble and shoot.",
                NPXG, 1, SHOTS, 1, TAKE_ONS, 0.8, CARRIES_INTO_BOX, 0.8, TOUCHES_ATT_BOX, 0.8, NP_GOALS, 0.6);
        role("wide-creator", "Wide creator", Position.W, "Creates from the flank.",
                XA, 1, KEY_PASSES, 1, CROSSES_INTO_BOX, 0.8, SCA, 0.8, CROSSES, 0.6, PROG_CARRIES, 0.6, ASSISTS, 0.5);

        role("poacher", "Poacher", Position.ST, "Lives in the box and finishes.",
                NPXG, 1, NP_GOALS, 1, SHOTS, 0.8, TOUCHES_ATT_BOX, 1);
        role("complete-forward", "Complete forward", Position.ST, "Scores and links play.",
                XA, 0.8, KEY_PASSES, 0.8, XG_CHAIN, 0.8, SCA, 0.8, PROG_RECEPTIONS, 0.6, NPXG, 0.6, ASSISTS, 0.4);
        role("target-forward", "Target forward", Position.ST, "Wins the ball in the air and holds it up.",
                AERIALS_WON, 1, TOUCHES_ATT_BOX, 0.6, SHOTS, 0.6, NPXG, 0.6);
    }

    public static List<Role> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static List<Role> of(Position p) {
        return ALL.stream().filter(r -> r.position() == p).toList();
    }

    public static Role byId(String id) {
        return ALL.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown role " + id));
    }

    private static final Map<Position, Map<Stat, Double>> POSITION_STATS = new EnumMap<>(Position.class);

    static {
        for (Position p : Position.values()) {
            Map<Stat, Double> m = new LinkedHashMap<>();
            for (Role r : of(p)) {
                r.weights().forEach((s, w) -> m.merge(s, Math.abs(w), Math::max));
            }
            POSITION_STATS.put(p, Collections.unmodifiableMap(m));
        }
    }

    /** Every stat any role at this position uses, with its largest absolute weight. */
    public static Map<Stat, Double> positionStats(Position p) {
        return POSITION_STATS.get(p);
    }

    /** Positions a successor may come from: the same one and its neighbours on the pitch. */
    public static List<Position> neighbours(Position p) {
        return switch (p) {
            case GK -> List.of(Position.GK);
            case CB -> List.of(Position.CB, Position.DM, Position.FB);
            case FB -> List.of(Position.FB, Position.CB, Position.W);
            case DM -> List.of(Position.DM, Position.CM, Position.CB);
            case CM -> List.of(Position.CM, Position.DM, Position.AM);
            case AM -> List.of(Position.AM, Position.CM, Position.W);
            case W -> List.of(Position.W, Position.AM, Position.ST);
            case ST -> List.of(Position.ST, Position.W, Position.AM);
        };
    }
}
