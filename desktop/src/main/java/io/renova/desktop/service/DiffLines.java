package io.renova.desktop.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A unified diff (git show --patch) as lines that know where they sit in the file before and after. */
public final class DiffLines {

    public enum Kind { FILE, HUNK, ADDED, REMOVED, CONTEXT, NOTE }

    /**
     * @param text   the line without its leading "+", "-" or space; for FILE, the path
     * @param before line number in the file before the change; 0 where the line did not exist
     * @param after  line number in the file after the change; 0 where the line no longer exists
     */
    public record Line(Kind kind, String text, int before, int after) {
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final Pattern FILE = Pattern.compile("^diff --git a/.* b/(.*)$");

    private DiffLines() {
    }

    /** @param limit lines to keep; a very large stage would otherwise fill the list */
    public static List<Line> parse(String diff, int limit) {
        List<Line> lines = new ArrayList<>();
        int before = 0;
        int after = 0;
        boolean inFile = false;
        for (String raw : diff.split("\n", -1)) {
            if (lines.size() >= limit) {
                lines.add(new Line(Kind.NOTE, "The rest of this stage's changes are not shown.", 0, 0));
                break;
            }
            Matcher file = FILE.matcher(raw);
            if (file.find()) {
                lines.add(new Line(Kind.FILE, file.group(1), 0, 0));
                inFile = true;
                continue;
            }
            if (!inFile || raw.startsWith("index ") || raw.startsWith("--- ") || raw.startsWith("+++ ")) {
                // The commit header before the first file, and the two file-name lines git repeats.
                continue;
            }
            Matcher hunk = HUNK.matcher(raw);
            if (hunk.find()) {
                before = Integer.parseInt(hunk.group(1));
                after = Integer.parseInt(hunk.group(2));
                lines.add(new Line(Kind.HUNK, raw, 0, 0));
            } else if (raw.startsWith("+")) {
                lines.add(new Line(Kind.ADDED, raw.substring(1), 0, after++));
            } else if (raw.startsWith("-")) {
                lines.add(new Line(Kind.REMOVED, raw.substring(1), before++, 0));
            } else if (raw.startsWith(" ")) {
                lines.add(new Line(Kind.CONTEXT, raw.substring(1), before++, after++));
            } else if (!raw.isEmpty()) {
                // "new file mode", "\ No newline at end of file", renames.
                lines.add(new Line(Kind.NOTE, raw, 0, 0));
            }
        }
        return lines;
    }
}
