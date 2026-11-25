package com.successor.engine.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Minimal RFC 4180 CSV reader/writer (quoted fields, doubled quotes, newlines inside quotes; {@code .gz} files are
 * decompressed on the fly). Malformed input fails loudly with the file and line number.
 */
public final class Csv {
    private Csv() {
    }

    /** One data row with access by header name. */
    public static final class Row {
        private final Map<String, Integer> index;
        private final List<String> cells;
        private final long line;

        Row(Map<String, Integer> index, List<String> cells, long line) {
            this.index = index;
            this.cells = cells;
            this.line = line;
        }

        /** The cell, or "" if the column is absent or the row is short. */
        public String get(String column) {
            Integer i = index.get(column);
            return i == null || i >= cells.size() ? "" : cells.get(i);
        }

        public boolean hasColumn(String column) {
            return index.containsKey(column);
        }

        /** Number in the cell (thousands separators and a leading '+' are accepted); NaN when empty or not numeric. */
        public double num(String column) {
            String s = get(column).trim();
            if (s.isEmpty()) {
                return Double.NaN;
            }
            s = s.replace(",", "");
            if (s.startsWith("+")) {
                s = s.substring(1);
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        public long line() {
            return line;
        }
    }

    /** Streams every row of {@code file} to {@code sink}; returns the number of rows. */
    public static long read(Path file, Consumer<Row> sink) {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            Parser p = new Parser(r, file);
            List<String> header = p.next();
            if (header == null) {
                return 0;
            }
            if (!header.isEmpty() && header.getFirst().startsWith("﻿")) {
                header.set(0, header.getFirst().substring(1));
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                index.putIfAbsent(header.get(i), i);
            }
            long rows = 0;
            List<String> cells;
            while ((cells = p.next()) != null) {
                if (cells.size() == 1 && cells.getFirst().isEmpty()) {
                    continue; // blank line
                }
                sink.accept(new Row(index, cells, p.line));
                rows++;
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static final class Parser {
        private final BufferedReader r;
        private final Path file;
        long line;

        Parser(BufferedReader r, Path file) {
            this.r = r;
            this.file = file;
        }

        List<String> next() throws IOException {
            String s = r.readLine();
            if (s == null) {
                return null;
            }
            line++;
            List<String> out = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            int i = 0;
            while (true) {
                if (i == s.length()) {
                    if (quoted) { // newline inside a quoted field
                        String more = r.readLine();
                        if (more == null) {
                            throw new IllegalArgumentException(file + ":" + line + ": unterminated quoted field");
                        }
                        line++;
                        cell.append('\n');
                        s = more;
                        i = 0;
                        continue;
                    }
                    out.add(cell.toString());
                    return out;
                }
                char c = s.charAt(i++);
                if (quoted) {
                    if (c == '"') {
                        if (i < s.length() && s.charAt(i) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"' && cell.isEmpty()) {
                    quoted = true;
                } else if (c == ',') {
                    out.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
        }
    }

    /** Quotes a value for writing when it contains a comma, quote or newline. */
    public static String quote(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
