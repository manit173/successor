package com.successor.app;

import javafx.application.Application;

/**
 * Plain main class: JavaFX refuses to start from a main class that extends {@link Application} when it is loaded
 * from the class path, so the app (and the packaged .app) launch through this one.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Successor");
        Application.launch(SuccessorApp.class, args);
    }
}
