package io.renova.desktop;

import javafx.application.Application;

/**
 * Starts the desktop app. A separate launcher class lets JavaFX run from the classpath (as jpackage bundles
 * it) without being set up as named modules.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(RenovaApp.class, args);
    }
}
