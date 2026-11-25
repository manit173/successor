package com.successor.server.api;

import com.successor.engine.bench.Benchmarks;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Benchmarks run by the Java backend on generated inputs.
 * <pre>
 *   GET  /api/benchmarks                    suites and the latest result of each
 *   POST /api/benchmarks/topk?quick=true    run one suite (quick sizes take seconds; full sizes up to a minute)
 * </pre>
 */
@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarksController {
    private final Map<String, Benchmarks.Suite> latest = new ConcurrentHashMap<>();

    public record Listing(List<Benchmarks.Info> suites, Map<String, Benchmarks.Suite> latest, String environment) {
    }

    @GetMapping
    public Listing list() {
        return new Listing(Benchmarks.list(), latest, Benchmarks.environment());
    }

    @PostMapping("/{id}")
    public Benchmarks.Suite run(@PathVariable String id, @RequestParam(defaultValue = "true") boolean quick) {
        synchronized (this) { // one suite at a time, so timings are not disturbed by each other
            Benchmarks.Suite s = Benchmarks.run(id, quick);
            latest.put(id, s);
            return s;
        }
    }
}
