package io.renova.core.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether the migrated application answered a step the same way as the original.
 *
 * <ul>
 *   <li>Status codes must match. Error statuses are compared by code only: their bodies are the
 *       servlet container's own error pages, which differ between container versions by design.</li>
 *   <li>Redirects must point to the same place (host, port and session ids ignored).</li>
 *   <li>The media type and charset of {@code Content-Type} must match.</li>
 *   <li>Bodies must match after removing the step's {@code ignore} patterns and normalising values
 *       that differ on every request (session ids, UUIDs, timestamps, whitespace). JSON is compared
 *       structurally. When the original's own two answers differ even so, its body is not compared.</li>
 * </ul>
 */
public final class ResponseComparator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONTEXT = 60;
    private static final List<Map.Entry<Pattern, String>> NORMALISERS = List.of(
            Map.entry(Pattern.compile("(?i);jsessionid=[\\w.-]+"), ""),
            Map.entry(Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "<uuid>"),
            Map.entry(Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?\\b"), "<timestamp>"),
            Map.entry(Pattern.compile("\\b1\\d{12}\\b"), "<epoch-millis>"),
            Map.entry(Pattern.compile("\\s+"), " "));

    private ResponseComparator() {
    }

    /** Compares a single-request scenario. */
    public static ScenarioResult compare(Scenario scenario, Exchange baseline, Exchange baselineAgain, Exchange candidate) {
        return compare(scenario, 0, baseline, baselineAgain, candidate);
    }

    public static ScenarioResult compare(Scenario scenario, int step, Exchange baseline, Exchange baselineAgain, Exchange candidate) {
        List<Pattern> ignore = scenario.steps().get(step).ignore().stream().map(Pattern::compile).toList();
        List<String> differences = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!baseline.responded()) {
            notes.add("the original application did not answer (" + baseline.error() + "); not compared");
        } else if (!candidate.responded()) {
            differences.add("the migrated application did not answer: " + candidate.error());
        } else if (baseline.status() != candidate.status()) {
            differences.add("status " + baseline.status() + " became " + candidate.status());
        } else if (baseline.status() >= 300 && baseline.status() < 400) {
            String before = location(baseline);
            String after = location(candidate);
            if (!before.equals(after)) {
                differences.add("redirect to " + before + " became " + after);
            }
        } else if (baseline.status() < 400) {
            String typeBefore = contentType(baseline);
            String typeAfter = contentType(candidate);
            if (!typeBefore.equals(typeAfter)) {
                differences.add("Content-Type " + quoted(typeBefore) + " became " + quoted(typeAfter));
            }
            String before = strip(baseline.text(), ignore);
            if (baselineAgain != null && baselineAgain.responded()
                    && !sameBody(before, strip(baselineAgain.text(), ignore), baseline, baselineAgain, typeBefore)) {
                notes.add("the original's body changes between identical requests; body not compared");
            } else {
                String after = strip(candidate.text(), ignore);
                if (!sameBody(before, after, baseline, candidate, typeBefore)) {
                    differences.add(bodyDifference(before, after));
                }
            }
        }
        return new ScenarioResult(scenario, step, baseline, candidate, differences, notes);
    }

    private static String strip(String text, List<Pattern> ignore) {
        String result = text;
        for (Pattern p : ignore) {
            result = p.matcher(result).replaceAll("<ignored>");
        }
        return result;
    }

    private static boolean sameBody(String aText, String bText, Exchange a, Exchange b, String contentType) {
        if (contentType.contains("json")) {
            try {
                return normalise(JSON.readTree(aText)).equals(normalise(JSON.readTree(bText)));
            } catch (Exception e) {
                // Not valid JSON on one side: compare as text.
            }
        }
        if (!isText(contentType)) {
            return Arrays.equals(a.body(), b.body());
        }
        return normalise(aText).equals(normalise(bText));
    }

    private static String bodyDifference(String baselineText, String candidateText) {
        String a = normalise(baselineText);
        String b = normalise(candidateText);
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - CONTEXT / 2);
        return "body differs at character " + i + ": " + quoted(snippet(a, from)) + " became " + quoted(snippet(b, from));
    }

    private static String snippet(String s, int from) {
        return from >= s.length() ? "" : s.substring(from, Math.min(s.length(), from + CONTEXT));
    }

    static String normalise(String text) {
        String result = text;
        for (Map.Entry<Pattern, String> n : NORMALISERS) {
            result = n.getKey().matcher(result).replaceAll(n.getValue());
        }
        return result.strip();
    }

    /**
     * Only the values that change on every request (session ids, UUIDs, timestamps), keeping whitespace:
     * for stored data, where "  Ama " and "Ama" are different values.
     */
    static String normaliseValue(String text) {
        String result = text;
        for (Map.Entry<Pattern, String> n : NORMALISERS) {
            if (!n.getKey().pattern().equals("\\s+")) {
                result = n.getKey().matcher(result).replaceAll(n.getValue());
            }
        }
        return result;
    }

    private static JsonNode normalise(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(normalise(node.asText()));
        }
        if (node.isObject()) {
            var copy = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                copy.set(field.getKey(), normalise(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = JSON.createArrayNode();
            node.forEach(n -> copy.add(normalise(n)));
            return copy;
        }
        return node;
    }

    private static String location(Exchange e) {
        String location = e.header("location");
        if (location == null) {
            return "(none)";
        }
        return location.replaceFirst("^https?://[^/]+", "").replaceAll("(?i);jsessionid=[\\w.-]+", "");
    }

    private static String contentType(Exchange e) {
        String type = e.header("content-type");
        return type == null ? "" : type.toLowerCase(Locale.ROOT).replace(" ", "").replace("\"", "");
    }

    private static boolean isText(String contentType) {
        return contentType.isEmpty() || contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("xml") || contentType.contains("javascript");
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }
}
