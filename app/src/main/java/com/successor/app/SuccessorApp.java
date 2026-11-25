package com.successor.app;

import atlantafx.base.controls.Sidebar;
import atlantafx.base.controls.SidebarNav;
import atlantafx.base.controls.SidebarSection;
import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Styles;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Successor window: unified title bar beside the traffic lights, a source-list sidebar, macOS menu bar, light/dark
 * appearance and accent colour taken from System Settings.
 *
 * <p>Options: {@code --server=<url>} use a running API instead of starting one, {@code --page=<id>} open a page,
 * {@code --theme=light|dark}, {@code --snapshot=<file.png>} save a screenshot once the server answers and quit
 * ({@code --delay=<ms>} extra wait first).
 */
public class SuccessorApp extends Application implements Ui.Services {

    private record Page(String id, String title, Ikon icon, String section, String blurb) {
    }

    private static final List<Page> PAGES = List.of(
            new Page("overview", "Overview", Feather.HOME, null,
                    "What is loaded and which algorithms drive which feature."),
            new Page("player", "Player", Feather.USER, "Scouting",
                    "Career by season, role and fit, percentiles within the position, next-season projection."),
            new Page("compare", "Compare", Feather.COLUMNS, "Scouting",
                    "Two or three players side by side on the stats that matter for their role."),
            new Page("successor", "Find a successor", Feather.SEARCH, "Scouting",
                    "Pick a player and his peak; find who projects closest to it across every league."),
            new Page("squad", "Squad planner", Feather.USERS, "Scouting",
                    "Several players leave: give each role a different replacement, maximising the weakest match."),
            new Page("algorithms", "Algorithms & benchmarks", Feather.ACTIVITY, "Under the hood",
                    "Which algorithm ran, how much work it did, and how it compares with its baseline."));

    private final Map<String, SidebarNav<String>> nav = new LinkedHashMap<>();
    private final StackPane content = new StackPane();
    private final Label status = new Label("Starting the server…");
    private final javafx.scene.shape.Circle statusDot = new javafx.scene.shape.Circle(4);
    private Stage stage;
    private StackPane root;
    private Sidebar<String> sidebar;
    private Node sidebarPane;
    private BorderPane shell;
    private volatile Backend backend;
    private JsonNode meta;
    private String current;
    private String theme = "system";
    private final javafx.scene.control.TextField search = new javafx.scene.control.TextField();
    private final javafx.scene.control.ContextMenu suggestions = new javafx.scene.control.ContextMenu();
    private final javafx.animation.PauseTransition typing =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(140));
    private JsonNode profile;
    private JsonNode analysis;
    private JsonNode projection;
    private SuccessorPage successorPage;
    private ComparePage comparePage;
    private SquadPage squadPage;
    private AlgorithmsPage algorithmsPage;
    private List<JsonNode> lastHits = List.of();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Map<String, String> args = getParameters().getNamed();
        theme = args.getOrDefault("theme", "system");

        MenuBar menus = buildMenus();
        menus.setUseSystemMenuBar(true);
        shell = new BorderPane();
        shell.getStyleClass().add("sc-window");
        shell.setTop(buildHeader());
        sidebarPane = buildSidebar();
        shell.setLeft(sidebarPane);
        shell.setCenter(content);
        root = new StackPane(shell, menus);
        menus.setManaged(false); // lives in the macOS menu bar; must stay in the scene graph but take no space

        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(SuccessorApp.class.getResource("successor.css").toExternalForm());
        stage.initStyle(StageStyle.EXTENDED);
        stage.setTitle("Successor");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setScene(scene);
        Platform.getPreferences().colorSchemeProperty().addListener((o, a, b) -> applyTheme());
        Platform.getPreferences().accentColorProperty().addListener((o, a, b) -> applyTheme());
        applyTheme();
        show(args.getOrDefault("page", "overview"));
        stage.show();

        connect(args.get("server"));
    }

    @Override
    public void stop() {
        if (backend != null) {
            backend.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ backend

    private void connect(String serverUrl) {
        setStatus("Starting the server…", "pending");
        Task<JsonNode> task = new Task<>() {
            @Override
            protected JsonNode call() {
                backend = serverUrl == null ? Backend.startEmbedded() : Backend.connect(serverUrl);
                return backend.get("/api/meta");
            }
        };
        task.setOnSucceeded(e -> {
            meta = task.getValue();
            String player = getParameters().getNamed().get("player");
            if (player != null) {
                openPlayer(player);
            }
            String successorOf = getParameters().getNamed().get("successor");
            if (successorOf != null) {
                findSuccessor(successorOf, successorOf);
            }
            String compareIds = getParameters().getNamed().get("compare");
            if (compareIds != null) {
                comparePage = new ComparePage(this);
                show("compare");
                comparePage.presetAll(List.of(compareIds.split(",")));
            }
            String squadIds = getParameters().getNamed().get("squad");
            if (squadIds != null) {
                squadPage = new SquadPage(this);
                show("squad");
                squadPage.planFor(List.of(squadIds.split(",")));
            }
            if ("algorithms".equals(getParameters().getNamed().get("page"))) {
                current = null;
                show("algorithms");
            }
            String tour = getParameters().getNamed().get("tour");
            if (tour != null) {
                tour(new java.util.ArrayDeque<>(List.of("player:tm:607223", "successor:tm:88755", "compare",
                        "squad", "algorithms", "overview")), tour.matches("\\d+") ? Integer.parseInt(tour) : 2500);
            }
            snapshotIfAsked();
            JsonNode ds = meta.path("dataset");
            String data = ds.path("status").asString().equals("ready")
                    ? String.format("%,d players · %,d player-seasons", ds.path("players").asInt(),
                    ds.path("playerSeasons").asInt())
                    : "no data loaded yet";
            setStatus("API " + backend.base().getAuthority() + " · " + data, "ok");
            refresh();
        });
        task.setOnFailed(e -> {
            setStatus("Server unavailable: " + task.getException().getMessage(), "error");
            snapshotIfAsked();
        });
        Thread t = new Thread(task, "backend-start");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Visits each page (running a real search, profile and plan), then quits: the training run that
     * scripts/package-mac.sh records into the app's ahead-of-time class cache.
     */
    private void tour(java.util.Deque<String> stops, int dwellMs) {
        if (stops.isEmpty()) {
            Platform.exit();
            return;
        }
        String stop = stops.removeFirst();
        if (stop.startsWith("player:")) {
            openPlayer(stop.substring(7));
        } else if (stop.startsWith("successor:")) {
            findSuccessor(stop.substring(10), stop.substring(10));
        } else if (stop.equals("compare")) {
            comparePage = new ComparePage(this);
            show("compare");
            comparePage.presetAll(List.of("tm:88755", "tm:607223"));
        } else if (stop.equals("squad")) {
            squadPage = new SquadPage(this);
            show("squad");
            squadPage.planFor(List.of("tm:88755", "tm:241641"));
        } else {
            current = null;
            show(stop);
        }
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(dwellMs));
        wait.setOnFinished(e -> tour(stops, dwellMs));
        wait.play();
    }

    private void snapshotIfAsked() {
        String file = getParameters().getNamed().get("snapshot");
        if (file == null) {
            return;
        }
        int delay = Integer.parseInt(getParameters().getNamed().getOrDefault("delay", "400"));
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(delay));
        wait.setOnFinished(e -> {
            Snapshots.save(root, java.nio.file.Path.of(file));
            Platform.exit();
        });
        wait.play();
    }

    private void setStatus(String text, String state) {
        status.setText(text);
        statusDot.getStyleClass().removeAll("sc-dot-ok", "sc-dot-pending", "sc-dot-error");
        statusDot.getStyleClass().add("sc-dot-" + state);
    }

    // ------------------------------------------------------------------------------------------------ chrome

    private HeaderBar buildHeader() {
        Button toggle = iconButton(Feather.SIDEBAR, "Show or hide the sidebar (⌃⌘S)", this::toggleSidebar);
        Label title = new Label("Successor");
        title.getStyleClass().add("sc-titlebar-title");
        HBox left = new HBox(6, toggle, title);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPadding(new Insets(0, 0, 0, 6));

        HBox right = new HBox(6, statusDot, status);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setPadding(new Insets(0, 14, 0, 0));
        status.getStyleClass().add("sc-status");

        search.setPromptText("Search players      ⌘K");
        search.getStyleClass().add("sc-search");
        search.setPrefWidth(360);
        search.setMaxWidth(360);
        search.textProperty().addListener((o, a, b) -> {
            typing.setOnFinished(e -> runSearch(b));
            typing.playFromStart();
        });
        search.setOnAction(e -> {
            if (!lastHits.isEmpty()) {
                openPlayer(lastHits.getFirst().path("id").asString());
            }
        });
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
                suggestions.hide();
                content.requestFocus();
            }
        });
        HBox center = new HBox(search);
        center.setAlignment(Pos.CENTER);
        HeaderBar header = new HeaderBar(left, center, right);
        header.getStyleClass().add("sc-titlebar");
        return header;
    }

    private Node buildSidebar() {
        sidebar = new Sidebar<>();
        String section = null;
        for (Page p : PAGES) {
            if (p.section() != null && !p.section().equals(section)) {
                section = p.section();
                sidebar.getItems().add(new SidebarSection<>(section));
            }
            SidebarNav<String> item = new SidebarNav<>(p.title(), icon(p.icon(), 15));
            item.setValue(p.id());
            nav.put(p.id(), item);
            sidebar.getItems().add(item);
        }
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.getValue() != null) {
                show(b.getValue());
            }
        });
        VBox pane = new VBox(sidebar);
        VBox.setVgrow(sidebar, Priority.ALWAYS);
        pane.getStyleClass().add("sc-sidebar-pane");
        pane.setPrefWidth(232);
        pane.setMinWidth(200);
        return pane;
    }

    private MenuBar buildMenus() {
        Menu view = new Menu("View");
        view.getItems().add(item("Toggle Sidebar",
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.CONTROL_DOWN),
                this::toggleSidebar));
        Menu go = new Menu("Go");
        go.getItems().add(item("Find Player…", new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                () -> search.requestFocus()));
        go.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        KeyCode[] digits = {KeyCode.DIGIT0, KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4,
                KeyCode.DIGIT5};
        for (int i = 0; i < PAGES.size(); i++) {
            Page p = PAGES.get(i);
            go.getItems().add(item(p.title(), new KeyCodeCombination(digits[i], KeyCombination.SHORTCUT_DOWN),
                    () -> show(p.id())));
        }
        return new MenuBar(view, go);
    }

    private void toggleSidebar() {
        shell.setLeft(shell.getLeft() == null ? sidebarPane : null);
    }

    // ------------------------------------------------------------------------------------------------ pages

    private void show(String id) {
        Page page = PAGES.stream().filter(p -> p.id().equals(id)).findFirst().orElse(PAGES.getFirst());
        current = page.id();
        Node node;
        if (page.id().equals("overview")) {
            node = overview();
        } else if (page.id().equals("player") && profile != null) {
            node = PlayerPage.build(profile, analysis, projection, this);
        } else if (page.id().equals("successor")) {
            if (successorPage == null) {
                successorPage = new SuccessorPage(this);
            }
            node = successorPage.node();
        } else if (page.id().equals("compare")) {
            if (comparePage == null) {
                comparePage = new ComparePage(this);
            }
            node = comparePage.node();
        } else if (page.id().equals("squad")) {
            if (squadPage == null) {
                squadPage = new SquadPage(this);
            }
            node = squadPage.node();
        } else if (page.id().equals("algorithms") && backend != null) {
            if (algorithmsPage == null) {
                algorithmsPage = new AlgorithmsPage(this);
            }
            node = algorithmsPage.node();
        } else {
            node = placeholder(page);
        }
        content.getChildren().setAll(node);
        // a quick fade so page changes feel smooth rather than abrupt
        node.setOpacity(0);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(180),
                node);
        fade.setToValue(1);
        javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(
                javafx.util.Duration.millis(220), node);
        slide.setFromY(8);
        slide.setToY(0);
        new javafx.animation.ParallelTransition(fade, slide).play();
        SidebarNav<String> item = nav.get(page.id());
        if (item != null && sidebar.getSelectionModel().getSelectedItem() != item) {
            sidebar.getSelectionModel().select(item);
        }
        stage.setTitle(page.id().equals("overview") ? "Successor"
                : page.id().equals("player") && profile != null ? profile.path("name").asString() + " — Successor"
                : page.title() + " — Successor");
    }

    // ------------------------------------------------------------------------------------------------ search

    private void runSearch(String text) {
        if (backend == null || text == null || text.isBlank()) {
            suggestions.hide();
            lastHits = List.of();
            return;
        }
        String q = java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
        background(() -> backend.get("/api/players/search?limit=8&q=" + q), r -> {
            if (!text.equals(search.getText())) {
                return; // the user kept typing; a newer search is coming
            }
            List<JsonNode> hits = new ArrayList<>();
            r.path("hits").forEach(hits::add);
            lastHits = hits;
            suggestions.getItems().clear();
            if (hits.isEmpty()) {
                MenuItem none = new MenuItem("No players match “" + text.trim() + "”");
                none.setDisable(true);
                suggestions.getItems().add(none);
            }
            for (JsonNode h : hits) {
                String detail = String.join(" · ", java.util.stream.Stream.of(h.path("position").asString(""),
                        h.path("club").asString(""), h.path("lastSeason").asString("")).filter(x -> !x.isBlank())
                        .toList());
                String alias = h.path("matchedAlias").asString("");
                Label main = new Label(h.path("name").asString());
                main.getStyleClass().add(Styles.TEXT_BOLD);
                Label sub = new Label(detail + (alias.isBlank() ? "" : "   (as " + alias + ")"));
                sub.getStyleClass().addAll("sc-muted", Styles.TEXT_SMALL);
                javafx.scene.control.CustomMenuItem item = new javafx.scene.control.CustomMenuItem(
                        new VBox(1, main, sub), true);
                item.setOnAction(e -> openPlayer(h.path("id").asString()));
                suggestions.getItems().add(item);
            }
            Label foot = new Label(String.format("%,d matches · trie visited %,d nodes", r.path("matched").asInt(),
                    r.path("trieWork").asLong()));
            foot.getStyleClass().addAll("sc-muted", Styles.TEXT_SMALL);
            javafx.scene.control.CustomMenuItem footer = new javafx.scene.control.CustomMenuItem(foot, false);
            footer.setDisable(true);
            suggestions.getItems().add(footer);
            if (!suggestions.isShowing()) {
                suggestions.show(search, javafx.geometry.Side.BOTTOM, 0, 4);
            }
        });
    }

    @Override
    public void openPlayer(String id) {
        suggestions.hide();
        search.clear();
        String path = "/api/players/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
        background(() -> {
            JsonNode p = backend.get(path);
            JsonNode a = backend.get(path + "/analysis");
            tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
            JsonNode pr;
            try {
                pr = backend.get(path + "/projection");
            } catch (Backend.ApiException e) { // e.g. no recent minutes: show why instead of failing the page
                pr = json.createObjectNode().put("error", e.getMessage());
            }
            tools.jackson.databind.node.ObjectNode both = json.createObjectNode();
            both.set("profile", p);
            both.set("analysis", a);
            both.set("projection", pr);
            return both;
        }, r -> {
            profile = r.path("profile");
            analysis = r.path("analysis");
            projection = r.path("projection");
            current = null;
            show("player");
        });
    }

    @Override
    public Backend backend() {
        return backend;
    }

    @Override
    public JsonNode meta() {
        return meta;
    }

    @Override
    public void call(java.util.function.Supplier<JsonNode> request, java.util.function.Consumer<JsonNode> onSuccess) {
        background(request, onSuccess);
    }

    @Override
    public void call(java.util.function.Supplier<JsonNode> request, java.util.function.Consumer<JsonNode> onSuccess,
                     java.util.function.Consumer<String> onError) {
        Task<JsonNode> t = new Task<>() {
            @Override
            protected JsonNode call() {
                return request.get();
            }
        };
        t.setOnSucceeded(e -> onSuccess.accept(t.getValue()));
        t.setOnFailed(e -> onError.accept(t.getException().getMessage()));
        Thread th = new Thread(t, "api-call");
        th.setDaemon(true);
        th.start();
    }

    @Override
    public void compareWith(String id, String name) {
        if (comparePage == null) {
            comparePage = new ComparePage(this);
        }
        comparePage.preset(id, name);
        show("compare");
    }

    @Override
    public void addToSquad(String id, String name) {
        if (squadPage == null) {
            squadPage = new SquadPage(this);
        }
        squadPage.add(id, name);
        show("squad");
    }

    @Override
    public void findSuccessor(String id, String name) {
        if (successorPage == null) {
            successorPage = new SuccessorPage(this);
        }
        show("successor");
        successorPage.setTarget(id, name);
    }

    /** Runs an API call off the UI thread; shows the server's explanation if it fails. */
    private void background(java.util.function.Supplier<JsonNode> call, java.util.function.Consumer<JsonNode> done) {
        Task<JsonNode> t = new Task<>() {
            @Override
            protected JsonNode call() {
                return call.get();
            }
        };
        t.setOnSucceeded(e -> done.accept(t.getValue()));
        t.setOnFailed(e -> setStatus(t.getException().getMessage(), "error"));
        Thread th = new Thread(t, "api-call");
        th.setDaemon(true);
        th.start();
    }

    private void refresh() {
        if (current != null) {
            String id = current;
            current = null;
            show(id);
        }
    }

    private Node overview() {
        // hero
        Label h = new Label("Successor");
        h.setStyle("-fx-font-size: 34px; -fx-font-weight: bold;");
        Label sub = new Label("Compare footballers within their position and role, project next season, and find who "
                + "could replace a player at his peak.");
        sub.setWrapText(true);
        sub.setStyle("-fx-font-size: 15px; -fx-opacity: 0.92;");
        VBox hero = new VBox(10, h, sub);
        hero.getStyleClass().add("sc-hero");
        hero.setPadding(new Insets(28, 30, 28, 30));
        boolean real = meta != null && !meta.path("dataset").path("origin").asString("").contains("sample")
                && !meta.path("dataset").path("origin").asString("").contains("synthetic");
        if (real) {
            Button s1 = heroButton("Find Kevin De Bruyne's successor", () -> findSuccessor("tm:88755",
                    "Kevin De Bruyne"));
            Button s2 = heroButton("Rayan Cherki", () -> openPlayer("tm:607223"));
            Button s3 = heroButton("De Bruyne, Rodri and Bernardo leave", () -> {
                squadPage = new SquadPage(this);
                show("squad");
                squadPage.planFor(List.of("tm:88755", "tm:357565", "tm:241641"));
            });
            HBox quick = new HBox(10, s1, s2, s3);
            VBox.setMargin(quick, new Insets(8, 0, 0, 0));
            hero.getChildren().add(quick);
        }

        VBox box = new VBox(20, hero);
        if (meta != null) {
            JsonNode ds = meta.path("dataset");
            int seasons = ds.path("seasons").size();
            HBox tiles = new HBox(14,
                    statTile(String.format("%,d", ds.path("players").asInt()), "players", Feather.USERS),
                    statTile(String.format("%,d", ds.path("playerSeasons").asInt()), "player-seasons",
                            Feather.LAYERS),
                    statTile(seasons == 0 ? "–" : ds.path("seasons").get(0).asString().substring(0, 4) + "–"
                            + ds.path("seasons").get(seasons - 1).asString().substring(5), seasons + " seasons",
                            Feather.CALENDAR),
                    statTile(Integer.toString(ds.path("leagues").size()), "leagues", Feather.GLOBE),
                    statTile(Integer.toString(meta.path("algorithms").size()), "assessed algorithms", Feather.CPU));
            tiles.getChildren().forEach(t -> HBox.setHgrow(t, Priority.ALWAYS));
            box.getChildren().add(tiles);
            box.getChildren().add(Ui.small("Data: " + ds.path("origin").asString() + ". Sources and licences in "
                    + "data/SOURCES.md."));
        }

        GridPane features = new GridPane();
        features.setHgap(14);
        features.setVgap(14);
        features.add(featureTile(Feather.SEARCH, "Find a successor", "Pick a player and his peak; see who projects "
                + "closest to it across 14 European leagues.", () -> show("successor")), 0, 0);
        features.add(featureTile(Feather.USER, "Player profiles", "Role, percentiles, career trend and next "
                + "season's projection. Search with ⌘K.", () -> search.requestFocus()), 1, 0);
        features.add(featureTile(Feather.COLUMNS, "Compare", "Two or three players side by side on the stats their "
                + "role is judged by.", () -> show("compare")), 0, 1);
        features.add(featureTile(Feather.USERS, "Squad planner", "Several players leave: one different replacement "
                + "each, maximising the weakest match.", () -> show("squad")), 1, 1);
        for (int c = 0; c < 2; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            features.getColumnConstraints().add(cc);
        }
        box.getChildren().add(features);

        if (meta != null) {
            GridPane grid = new GridPane();
            grid.setHgap(18);
            grid.setVgap(8);
            String[] head = {"Algorithm", "Syllabus", "Used for", "Baseline"};
            for (int c = 0; c < head.length; c++) {
                Label l = new Label(head[c]);
                l.getStyleClass().add("sc-table-head");
                grid.add(l, c, 0);
            }
            int r = 1;
            for (JsonNode a : meta.path("algorithms")) {
                String[] cells = {a.path("name").asString(), a.path("unit").asString(), a.path("usedFor").asString(),
                        a.path("baseline").asString()};
                for (int c = 0; c < cells.length; c++) {
                    Label l = new Label(cells[c]);
                    l.setWrapText(true);
                    if (c == 0) {
                        l.getStyleClass().add(Styles.TEXT_BOLD);
                    }
                    grid.add(l, c, r);
                }
                r++;
            }
            Button more = new Button("Algorithms & benchmarks →");
            more.getStyleClass().addAll(Styles.FLAT, Styles.ACCENT);
            more.setOnAction(e -> show("algorithms"));
            HBox head2 = new HBox(10, Ui.title("Algorithms behind the features", Styles.TITLE_4), Ui.grow(), more);
            head2.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(Ui.card(head2, grid));
        }
        box.setPadding(new Insets(28, 34, 32, 34));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(atlantafx.base.theme.Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    private static Button heroButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: rgba(255,255,255,0.2); -fx-text-fill: white; -fx-background-radius: 8px;"
                + " -fx-border-color: rgba(255,255,255,0.35); -fx-border-radius: 8px;");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Node statTile(String value, String label, Ikon ikon) {
        FontIcon i = icon(ikon, 18);
        i.getStyleClass().add("sc-tile-icon");
        Label v = new Label(value);
        v.getStyleClass().add("sc-big-number");
        VBox t = new VBox(4, i, v, Ui.small(label));
        t.getStyleClass().add("sc-tile");
        t.setStyle("-fx-cursor: default;");
        t.setPadding(new Insets(14, 16, 14, 16));
        t.setMinWidth(150);
        return t;
    }

    private static Node featureTile(Ikon ikon, String title, String text, Runnable action) {
        FontIcon i = icon(ikon, 22);
        i.getStyleClass().add("sc-tile-icon");
        Label t = new Label(title);
        t.getStyleClass().add(Styles.TITLE_4);
        Label d = Ui.muted(text);
        HBox row = new HBox(14, i, new VBox(4, t, d));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sc-tile");
        row.setPadding(new Insets(18, 20, 18, 20));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setOnMouseClicked(e -> action.run());
        return row;
    }

    private Node placeholder(Page page) {
        FontIcon big = icon(page.icon(), 44);
        big.getStyleClass().add("sc-empty-icon");
        Label h = new Label(page.title());
        h.getStyleClass().add(Styles.TITLE_2);
        Label blurb = new Label(page.blurb());
        blurb.getStyleClass().add("sc-muted");
        blurb.setWrapText(true);
        blurb.setMaxWidth(460);
        blurb.setAlignment(Pos.CENTER);
        Label soon = new Label("Being built");
        soon.getStyleClass().addAll(Styles.ACCENT, "sc-chip");
        VBox box = new VBox(12, big, h, blurb, soon);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ------------------------------------------------------------------------------------------------ theme

    private static volatile boolean darkNow;

    /** Whether the window is currently dark (system appearance or --theme), for canvas-drawn charts. */
    static boolean darkTheme() {
        return darkNow;
    }

    private void applyTheme() {
        boolean dark = switch (theme) {
            case "dark" -> true;
            case "light" -> false;
            default -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        };
        darkNow = dark;
        Application.setUserAgentStylesheet(dark ? new CupertinoDark().getUserAgentStylesheet()
                : new CupertinoLight().getUserAgentStylesheet());
        try {
            HeaderBar.setSystemColorScheme(stage, dark ? ColorScheme.DARK : ColorScheme.LIGHT);
        } catch (RuntimeException ignored) {
            // older platforms keep the default traffic-light appearance
        }
        Color c = Platform.getPreferences().getAccentColor();
        if (c != null && root != null) {
            Color fg = dark ? c.deriveColor(0, 0.85, 1.25, 1) : c;
            root.setStyle("-color-accent-emphasis: " + css(c) + "; -color-accent-fg: " + css(fg) + ";"
                    + "-color-accent-muted: " + css(c.deriveColor(0, 1, 1, 0.45)) + ";"
                    + "-color-accent-subtle: " + css(c.deriveColor(0, 1, 1, dark ? 0.22 : 0.13)) + ";");
        }
    }

    private static String css(Color c) {
        return String.format("rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static FontIcon icon(Ikon ikon, int size) {
        FontIcon i = new FontIcon(ikon);
        i.setIconSize(size);
        return i;
    }

    private static Button iconButton(Ikon ikon, String tip, Runnable action) {
        Button b = new Button(null, icon(ikon, 16));
        b.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private static MenuItem item(String text, KeyCombination key, Runnable action) {
        MenuItem m = new MenuItem(text);
        m.setAccelerator(key);
        m.setOnAction(e -> action.run());
        return m;
    }

    @SuppressWarnings("unused")
    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
