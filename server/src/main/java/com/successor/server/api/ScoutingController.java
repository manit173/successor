package com.successor.server.api;

import com.successor.engine.ScoutingEngine;
import com.successor.engine.scouting.Roles;
import com.successor.engine.scouting.Scout;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Scouting endpoints.
 * <pre>
 *   GET  /api/roles                       role templates (stats and weights) per position
 *   GET  /api/players/{id}/analysis       every season: role fits and percentiles within the position
 *   POST /api/successor                   {"playerId":"tm:88755","maxAge":23,"k":10,...}
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class ScoutingController {
    private final ScoutingEngine engine;

    public ScoutingController(ScoutingEngine engine) {
        this.engine = engine;
    }

    public record RoleView(String id, String name, String position, String description, Map<String, Double> weights) {
    }

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return Roles.all().stream().map(r -> new RoleView(r.id(), r.name(), r.position().name(), r.description(),
                r.weights().entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey().key(),
                        Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new)))).toList();
    }

    @GetMapping("/players/{id}/analysis")
    public List<Scout.SeasonAnalysis> analysis(@PathVariable String id) {
        engine.scout().player(id); // 404 for unknown ids
        return engine.scout().analyse(id);
    }

    @PostMapping("/successor")
    public Scout.Result successor(@RequestBody Scout.Request request) {
        return engine.scout().successor(request);
    }
}
