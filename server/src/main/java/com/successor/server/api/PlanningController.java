package com.successor.server.api;

import com.successor.engine.ScoutingEngine;
import com.successor.engine.scouting.Comparer;
import com.successor.engine.scouting.Projector;
import com.successor.engine.scouting.Scout;
import com.successor.engine.scouting.SquadPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Projection, comparison and squad planning.
 * <pre>
 *   GET  /api/players/{id}/projection?position=AM   next season, per stat, with an 80% range
 *   GET  /api/backtest                               projection accuracy on past seasons vs baselines
 *   POST /api/compare     {"ids":["tm:88755","tm:607223"]}
 *   POST /api/squad-plan  {"departures":["tm:88755","tm:357565"],"maxAge":26}
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class PlanningController {
    private final ScoutingEngine engine;

    public PlanningController(ScoutingEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/players/{id}/projection")
    public Scout.ProjectionView projection(@PathVariable String id,
                                           @RequestParam(required = false) String position) {
        return engine.scout().projection(id, position);
    }

    @GetMapping("/backtest")
    public Projector.Backtest backtest() {
        return engine.backtest();
    }

    public record CompareRequest(List<String> ids, String position) {
    }

    @PostMapping("/compare")
    public Comparer.Result compare(@RequestBody CompareRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("a JSON body with ids is required");
        }
        return engine.comparer().compare(request.ids(), request.position());
    }

    @PostMapping("/squad-plan")
    public SquadPlanner.Result squadPlan(@RequestBody SquadPlanner.Request request) {
        return engine.squadPlanner().plan(request);
    }
}
