package com.successor.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the real server on a random port and talks to it over HTTP, exactly as the app does. */
class ApiSmokeTest {
    private static ConfigurableApplicationContext ctx;
    private static String base;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        // the committed synthetic sample, so the test does not depend on downloaded data
        ctx = SpringApplication.run(SuccessorServer.class, "--server.port=0", "--successor.data=../data/sample");
        base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metaDescribesServerDataAndAlgorithms() throws Exception {
        HttpResponse<String> r = get("/api/meta");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"name\":\"Successor\""), r.body());
        assertTrue(r.body().contains("\"dataset\""), r.body());
        assertTrue(r.body().contains("Randomized quickselect"), r.body());
    }

    @Test
    void searchFindsPlayersAndRejectsBlankQueries() throws Exception {
        HttpResponse<String> r = get("/api/players/search?q=a&limit=3");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"hits\""), r.body());
        HttpResponse<String> blank = get("/api/players/search?q=");
        assertEquals(400, blank.statusCode());
        assertTrue(blank.body().contains("at least one letter"), blank.body());
        assertEquals(400, get("/api/players/search?q=ab&limit=0").statusCode());
    }

    @Test
    void profileReturnsSeasonsAndUnknownIdIs404() throws Exception {
        HttpResponse<String> r = get("/api/players/syn:0");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"seasons\""), r.body());
        assertTrue(!r.body().contains("NaN"), "missing stats are omitted, never NaN");
        HttpResponse<String> missing = get("/api/players/tm:does-not-exist");
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("no player with id"), missing.body());
    }

    private static HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void successorSearchRanksCandidatesAndValidatesInput() throws Exception {
        HttpResponse<String> r = post("/api/successor", "{\"playerId\":\"syn:0\",\"k\":5}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"candidates\"") && r.body().contains("\"peak\""), r.body());
        assertEquals(400, post("/api/successor", "{\"playerId\":\"syn:0\",\"k\":0}").statusCode());
        assertEquals(404, post("/api/successor", "{\"playerId\":\"nobody\"}").statusCode());
        assertEquals(400, post("/api/successor", "{\"playerId\":\"syn:0\",\"position\":\"XX\"}").statusCode());
        assertEquals(200, get("/api/roles").statusCode());
        assertEquals(200, get("/api/players/syn:0/analysis").statusCode());
    }

    @Test
    void projectionCompareSquadPlanAndBacktestAnswer() throws Exception {
        HttpResponse<String> pr = get("/api/players/syn:0/projection");
        assertEquals(200, pr.statusCode(), pr.body());
        assertTrue(pr.body().contains("\"stats\""), pr.body());
        assertEquals(200, post("/api/compare", "{\"ids\":[\"syn:0\",\"syn:1\"]}").statusCode());
        assertEquals(400, post("/api/compare", "{\"ids\":[\"syn:0\"]}").statusCode());
        assertEquals(400, post("/api/compare", "{not json").statusCode());
        HttpResponse<String> sq = post("/api/squad-plan", "{\"departures\":[\"syn:0\"],\"candidatesPerRole\":5}");
        assertEquals(200, sq.statusCode(), sq.body());
        assertTrue(sq.body().contains("\"matching\""), sq.body());
        assertEquals(400, post("/api/squad-plan", "{\"departures\":[]}").statusCode());
        HttpResponse<String> bt = get("/api/backtest");
        assertEquals(200, bt.statusCode());
        assertTrue(bt.body().contains("MARCEL") && bt.body().contains("NAIVE"), bt.body());
    }

    @Test
    void benchmarksListAndRunOneSuite() throws Exception {
        assertEquals(200, get("/api/benchmarks").statusCode());
        HttpResponse<String> r = post("/api/benchmarks/bottleneck?quick=true", "");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"rows\""), r.body());
        assertEquals(404, post("/api/benchmarks/nope", "").statusCode());
    }

    @Test
    void unknownEndpointIsAProblemDocumentNotAStackTrace() throws Exception {
        HttpResponse<String> r = get("/api/does-not-exist");
        assertEquals(404, r.statusCode());
        assertTrue(!r.body().contains("Exception"), r.body());
    }
}
