package com.successor.app;

import com.successor.server.SuccessorServer;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The app's link to the Java backend. Either starts the API in this process on a free loopback port, or connects
 * to one already running ({@code --server=http://127.0.0.1:8080}). Either way every request goes over HTTP, so the
 * app is a genuine client of the REST API and never calls the engine directly.
 */
final class Backend implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final ConfigurableApplicationContext embedded;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Backend(URI base, ConfigurableApplicationContext embedded) {
        this.base = base;
        this.embedded = embedded;
    }

    /**
     * Where the data lives: {@code -Dsuccessor.data}, else {@code data/processed} when run from the repository, else
     * {@code ~/Library/Application Support/Successor/data} (where scripts/package-mac.sh installs the real dataset on
     * this Mac), else the synthetic sample bundled in the app ({@code -Dsuccessor.sample}); null lets the engine fall
     * back to generating one.
     */
    static String dataDir() {
        String explicit = System.getProperty("successor.data");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        java.nio.file.Path support = java.nio.file.Path.of(System.getProperty("user.home"), "Library",
                "Application Support", "Successor", "data");
        // run from the repository: its freshly built data first; packaged app: the installed copy
        for (java.nio.file.Path p : java.util.List.of(java.nio.file.Path.of("data/processed"), support,
                java.nio.file.Path.of("data/sample"))) {
            if (java.nio.file.Files.isRegularFile(p.resolve("players.csv"))) {
                return p.toString();
            }
        }
        String sample = System.getProperty("successor.sample");
        return sample != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(sample, "players.csv"))
                ? sample : null;
    }

    /** Starts the API inside this JVM (a few seconds); call off the JavaFX thread. */
    static Backend startEmbedded() {
        String data = dataDir();
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--server.port=0"));
        if (data != null) {
            args.add("--successor.data=" + data);
        }
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SuccessorServer.class)
                .headless(false) // keep AWT usable for the UI; Spring Boot defaults to headless
                .bannerMode(Banner.Mode.OFF)
                .run(args.toArray(new String[0]));
        String port = ctx.getEnvironment().getProperty("local.server.port");
        return new Backend(URI.create("http://127.0.0.1:" + port), ctx);
    }

    static Backend connect(String url) {
        return new Backend(URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url), null);
    }

    URI base() {
        return base;
    }

    boolean embedded() {
        return embedded != null;
    }

    /** GET a JSON resource. A non-2xx answer becomes an {@link ApiException} carrying the server's explanation. */
    JsonNode get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    /** POST a JSON body. */
    JsonNode post(String path, Object body) {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
            if (r.statusCode() / 100 != 2) {
                String detail = body.path("detail").asString(r.body());
                throw new ApiException(r.statusCode(), detail);
            }
            return body;
        } catch (IOException e) {
            throw new ApiException(0, "Cannot reach the server at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "Interrupted");
        }
    }

    @Override
    public void close() {
        if (embedded != null) {
            embedded.close();
        }
    }

    /** An error answer from the API (status 0 = could not connect). */
    static final class ApiException extends RuntimeException {
        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
