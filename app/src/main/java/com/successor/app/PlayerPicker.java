package com.successor.app;

import atlantafx.base.theme.Styles;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/** A text field that searches players as you type (trie search via the API) and reports the one picked. */
final class PlayerPicker extends TextField {
    private final ContextMenu menu = new ContextMenu();
    private String pickedId;

    PlayerPicker(Ui.Services app, String prompt, BiConsumer<String, String> onPick) {
        setPromptText(prompt);
        setPrefWidth(300);
        textProperty().addListener((o, a, text) -> {
            if (!isFocused() || text == null || text.isBlank() || app.backend() == null) {
                menu.hide();
                return;
            }
            String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
            app.call(() -> app.backend().get("/api/players/search?limit=6&q=" + q), r -> {
                if (!text.equals(getText())) {
                    return; // typing continued: a newer search is on its way
                }
                menu.getItems().clear();
                for (JsonNode hit : r.path("hits")) {
                    Label main = new Label(hit.path("name").asString());
                    main.getStyleClass().add(Styles.TEXT_BOLD);
                    Label detail = Ui.small(hit.path("position").asString("") + " · " + hit.path("club").asString("")
                            + " · " + hit.path("lastSeason").asString(""));
                    CustomMenuItem item = new CustomMenuItem(new VBox(1, main, detail), true);
                    item.setOnAction(e -> {
                        pickedId = hit.path("id").asString();
                        setText(hit.path("name").asString());
                        menu.hide();
                        onPick.accept(pickedId, hit.path("name").asString());
                    });
                    menu.getItems().add(item);
                }
                if (menu.getItems().isEmpty()) {
                    menu.hide();
                } else if (!menu.isShowing()) {
                    menu.show(this, javafx.geometry.Side.BOTTOM, 0, 4);
                }
            });
        });
    }

    String pickedId() {
        return pickedId;
    }

    void set(String id, String name) {
        pickedId = id;
        setText(name);
    }

    void reset() {
        pickedId = null;
        clear();
    }
}
