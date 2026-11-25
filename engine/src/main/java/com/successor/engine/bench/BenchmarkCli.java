package com.successor.engine.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Runs the benchmark suites and writes {@code results.md} and {@code results.csv}.
 * <pre>java -cp engine/target/classes com.successor.engine.bench.BenchmarkCli docs/benchmarks full [suite]</pre>
 */
public final class BenchmarkCli {
    private BenchmarkCli() {
    }

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/benchmarks");
        boolean quick = args.length > 1 && args[1].equals("quick");
        List<String> ids = args.length > 2 ? List.of(args[2]) : Benchmarks.list().stream().map(Benchmarks.Info::id)
                .toList();
        StringBuilder md = new StringBuilder("# Benchmark results\n\n");
        md.append(Benchmarks.environment()).append(" · ").append(quick ? "quick" : "full").append(" sizes · ")
                .append(Instant.now()).append("\n\nTimes are medians of repeated runs after a warm-up; work counts "
                        + "are exact and machine-independent.\n\n");
        StringBuilder csv = new StringBuilder("suite,size,n,variant,baseline,ms,work,answer,answers_agree\n");
        for (String id : ids) {
            long t0 = System.nanoTime();
            Benchmarks.Suite s = Benchmarks.run(id, quick);
            System.out.printf(Locale.ROOT, "%-11s %.1f s%n", id, (System.nanoTime() - t0) / 1e9);
            md.append(Benchmarks.markdown(s));
            for (Benchmarks.Row r : s.rows()) {
                for (Benchmarks.Cell c : r.cells()) {
                    csv.append(String.join(",", s.id(), '"' + r.size() + '"', Long.toString(r.n()), '"' + c.variant()
                                    + '"', Boolean.toString(c.baseline()), Double.toString(c.ms()),
                            Long.toString(c.work()), '"' + c.answer().replace("\"", "'") + '"',
                            String.valueOf(r.answersAgree()))).append('\n');
                }
            }
        }
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("results.md"), md);
            Files.writeString(out.resolve("results.csv"), csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("wrote " + out.resolve("results.md") + " and results.csv");
    }
}
