package com.successor.engine.phone;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** A minimal JSON writer for the phone export (the engine has no JSON library). NaN is written as null. */
final class Json {
    private final StringBuilder sb = new StringBuilder(1 << 20);

    String done() {
        return sb.toString();
    }

    Json raw(String s) {
        sb.append(s);
        return this;
    }

    Json str(String s) {
        if (s == null) {
            sb.append("null");
            return this;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return this;
    }

    Json num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            sb.append(v); // Double.toString round-trips exactly
        }
        return this;
    }

    Json key(String k) {
        str(k);
        sb.append(':');
        return this;
    }

    Json strings(Collection<String> xs) {
        sb.append('[');
        int i = 0;
        for (String x : xs) {
            if (i++ > 0) {
                sb.append(',');
            }
            str(x);
        }
        sb.append(']');
        return this;
    }

    Json ints(int[] xs) {
        sb.append('[');
        for (int i = 0; i < xs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(xs[i]);
        }
        sb.append(']');
        return this;
    }

    Json nums(double[] xs) {
        sb.append('[');
        for (int i = 0; i < xs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            num(xs[i]);
        }
        sb.append(']');
        return this;
    }

    Json doubles(Map<String, Double> m) {
        sb.append('{');
        int i = 0;
        for (Map.Entry<String, Double> e : m.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            key(e.getKey()).num(e.getValue());
        }
        sb.append('}');
        return this;
    }

    Json comma() {
        sb.append(',');
        return this;
    }
}
