package io.renova.desktop;

import io.renova.desktop.service.MigrationRun;

import java.nio.file.Path;

/** What the views can ask the application window to do. */
public interface Navigator {

    void home();

    void settings();

    void openProject(Path path);

    /** Runs {@code work} on a background thread and shows the run. */
    void startRun(MigrationRun run, Runnable work);

    void showRun(MigrationRun run);

    /** Opens a file or folder with the system's default application. */
    void openPath(Path path);

    void snapshot(String name, double delaySeconds);

    /** Whether to start a migration (default options, no AI) as soon as a project is assessed: --migrate. */
    boolean autoMigrate();
}
