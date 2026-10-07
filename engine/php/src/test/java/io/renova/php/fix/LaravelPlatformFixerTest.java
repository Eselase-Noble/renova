package io.renova.php.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What the fixer reads from a site before it moves it into Laravel. */
class LaravelPlatformFixerTest {

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void findsTheVariablesTheSitesFunctionsTakeFromTheGlobalScope(@TempDir Path site) throws Exception {
        write(site, "includes/layout.php", """
                <?php
                function page_top($title) {
                    global $SITE_NAME, $menu;
                    echo $GLOBALS['theme'] . $_SESSION["user"] . $GLOBALS[ "lang" ];
                }
                """);
        write(site, "lib/db.inc", "<?php function db() { global $DB_FILE; }\n");
        write(site, "vendor/x/y.php", "<?php function f() { global $notOurs; }\n");
        write(site, "notes.txt", "global $notCode;");

        assertThat(LaravelPlatformFixer.globals(site)).containsExactly("DB_FILE", "SITE_NAME", "lang", "menu", "theme");
    }

    @Test
    void theDocumentRootIsTheFolderWithTheIndexPage(@TempDir Path tmp) throws Exception {
        Path flat = Files.createDirectories(tmp.resolve("flat"));
        write(flat, "index.php", "<?php\n");
        write(flat, "public/index.php", "<?php\n");
        Path split = Files.createDirectories(tmp.resolve("split"));
        write(split, "lib/app.php", "<?php\n");
        write(split, "public_html/index.php", "<?php\n");

        assertThat(LaravelPlatformFixer.documentRoot(flat)).isEmpty();
        assertThat(LaravelPlatformFixer.documentRoot(split)).isEqualTo("public_html");
    }
}
