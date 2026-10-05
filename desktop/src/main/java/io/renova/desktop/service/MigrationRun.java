package io.renova.desktop.service;

import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.time.Instant;

/** One migration started in this session, observed by the views while it runs on a background thread. */
public final class MigrationRun {

    public enum State { RUNNING, PASSED, FAILED, ERROR }

    private final String projectName;
    private final MigrationOptions options;
    private final Instant started = Instant.now();
    private final ObservableList<String> log = FXCollections.observableArrayList();
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.RUNNING);
    private MigrationOutcome outcome;
    private String error;
    private Instant finished;

    public MigrationRun(String projectName, MigrationOptions options) {
        this.projectName = projectName;
        this.options = options;
    }

    /** Safe to call from any thread. */
    public void log(String line) {
        Platform.runLater(() -> log.add(line));
    }

    public void finish(MigrationOutcome outcome, State result) {
        Platform.runLater(() -> {
            this.outcome = outcome;
            this.finished = Instant.now();
            state.set(result);
        });
    }

    public void fail(String message) {
        Platform.runLater(() -> {
            this.error = message;
            this.finished = Instant.now();
            log.add("Error: " + message);
            state.set(State.ERROR);
        });
    }

    public String projectName() {
        return projectName;
    }

    public MigrationOptions options() {
        return options;
    }

    public Path workspace() {
        return options.outputDir();
    }

    public ObservableList<String> log() {
        return log;
    }

    public ObjectProperty<State> state() {
        return state;
    }

    public MigrationOutcome outcome() {
        return outcome;
    }

    public String error() {
        return error;
    }

    public Instant started() {
        return started;
    }

    public Instant finished() {
        return finished;
    }
}
