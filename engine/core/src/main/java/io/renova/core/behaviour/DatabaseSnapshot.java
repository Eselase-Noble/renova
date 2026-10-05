package io.renova.core.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rows of every table in a sandbox PostgreSQL database, and the comparison of what two
 * applications changed: for each table, the rows each added and removed during the same scenario.
 * Values are compared exactly, apart from ids, UUIDs and timestamps that differ on every run.
 */
public final class DatabaseSnapshot {

    public static final String USER = "renova";
    public static final String DATABASE = "app";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SHOWN_ROWS = 5;

    private DatabaseSnapshot() {
    }

    /** Table name to its rows, each a canonical JSON object. */
    public static Map<String, List<String>> take(String container) throws IOException, InterruptedException {
        Map<String, List<String>> tables = new TreeMap<>();
        String names = psql(container, "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1");
        for (String table : names.lines().filter(l -> !l.isBlank()).toList()) {
            String rows = psql(container, "SELECT row_to_json(t)::text FROM \"" + table.replace("\"", "\"\"") + "\" t");
            tables.put(table, rows.lines().filter(l -> !l.isBlank()).toList());
        }
        return tables;
    }

    private static String psql(String container, String sql) throws IOException, InterruptedException {
        DockerSandbox.Result result = DockerSandbox.exec(List.of("docker", "exec", container, "psql", "-U", USER, "-d", DATABASE,
                "-At", "-v", "ON_ERROR_STOP=1", "-c", sql), null, 120);
        if (result.exit() != 0) {
            throw new IOException("Could not read the sandbox database: " + result.output().strip());
        }
        return result.output();
    }

    /**
     * Differences between what the original and the migrated application changed in their databases
     * during one scenario. Ignored columns are left out; values are normalised like response bodies.
     */
    public static List<String> compare(Map<String, List<String>> baselineBefore, Map<String, List<String>> baselineAfter,
                                       Map<String, List<String>> candidateBefore, Map<String, List<String>> candidateAfter,
                                       List<String> ignoreColumns) {
        List<String> differences = new ArrayList<>();
        Set<String> tables = new TreeSet<>();
        List.of(baselineBefore, baselineAfter, candidateBefore, candidateAfter).forEach(m -> tables.addAll(m.keySet()));
        for (String table : tables) {
            List<String> baselineRowsBefore = canonical(baselineBefore.get(table), ignoreColumns);
            List<String> baselineRowsAfter = canonical(baselineAfter.get(table), ignoreColumns);
            List<String> candidateRowsBefore = canonical(candidateBefore.get(table), ignoreColumns);
            List<String> candidateRowsAfter = canonical(candidateAfter.get(table), ignoreColumns);
            List<String> addedBefore = minus(baselineRowsAfter, baselineRowsBefore);
            List<String> addedAfter = minus(candidateRowsAfter, candidateRowsBefore);
            List<String> removedBefore = minus(baselineRowsBefore, baselineRowsAfter);
            List<String> removedAfter = minus(candidateRowsBefore, candidateRowsAfter);
            if (!addedBefore.equals(addedAfter)) {
                differences.add("table " + table + ": the original added " + show(addedBefore) + "; the migrated app added "
                        + show(addedAfter));
            }
            if (!removedBefore.equals(removedAfter)) {
                differences.add("table " + table + ": the original removed " + show(removedBefore) + "; the migrated app removed "
                        + show(removedAfter));
            }
        }
        return differences;
    }

    private static List<String> canonical(List<String> rows, List<String> ignoreColumns) {
        if (rows == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String row : rows) {
            try {
                JsonNode node = JSON.readTree(row);
                if (node instanceof ObjectNode object) {
                    ignoreColumns.forEach(object::remove);
                    Map<String, String> sorted = new TreeMap<>();
                    object.fields().forEachRemaining(f -> sorted.put(f.getKey(),
                            f.getValue().isTextual() ? ResponseComparator.normaliseValue(f.getValue().asText()) : f.getValue().toString()));
                    result.add(JSON.writeValueAsString(sorted));
                } else {
                    result.add(row);
                }
            } catch (IOException e) {
                result.add(row);
            }
        }
        result.sort(null);
        return result;
    }

    /** Multiset difference: rows of {@code a} not matched by a row of {@code b}, sorted. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> remaining = new HashMap<>();
        b.forEach(r -> remaining.merge(r, 1, Integer::sum));
        List<String> result = new ArrayList<>();
        for (String row : a) {
            Integer n = remaining.get(row);
            if (n == null || n == 0) {
                result.add(row);
            } else {
                remaining.put(row, n - 1);
            }
        }
        result.sort(null);
        return result;
    }

    private static String show(List<String> rows) {
        if (rows.isEmpty()) {
            return "no rows";
        }
        String list = String.join(", ", rows.subList(0, Math.min(SHOWN_ROWS, rows.size())));
        return rows.size() + " row(s): " + list + (rows.size() > SHOWN_ROWS ? ", …" : "");
    }
}
