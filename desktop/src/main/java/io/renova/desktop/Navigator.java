package io.renova.desktop;

import io.renova.desktop.service.MigrationRun;

import java.nio.file.Path;

/** What the views can ask the application window to do. */
public interface Navigator {

    /** The start screen: what has been migrated and what is running. */
    void overview();

    /** Every project under a folder, assessed and ranked. */
    void portfolio();

    /** Projects opened before, and opening another. */
    void home();

    void settings();

    void openProject(Path path);

    /** Runs {@code work} on a background thread and shows the run. */
    void startRun(MigrationRun run, Runnable work);

    void showRun(MigrationRun run);

    /** Past migrations from this app. */
    void migrations();

    /** A finished migration, from its migrated copy. */
    void showWorkspace(Path workspace);

    /** Removes a migration from the history (the migrated copy stays) and shows the list. */
    void forget(Path workspace);

    io.renova.desktop.service.Engine engine();

    /** Opens a file or folder with the system's default application. */
    void openPath(Path path);

    void snapshot(String name, double delaySeconds);

    /** The tab a finished migration opens on (--tab), or null for the first. */
    String startTab();

    /** Whether to start a migration (default options, no AI) as soon as a project is assessed: --migrate. */
    boolean autoMigrate();
}
