package io.renova.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lenient version comparison for the messy version strings found in legacy builds. */
public final class Versions {

    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private Versions() {
    }

    /**
     * Compares the numeric segments of two versions ("4.3.30.RELEASE" vs "6"); missing segments
     * count as 0 and qualifiers are ignored.
     */
    public static int compare(String a, String b) {
        Matcher ma = NUMBERS.matcher(a);
        Matcher mb = NUMBERS.matcher(b);
        while (true) {
            boolean ha = ma.find();
            boolean hb = mb.find();
            if (!ha && !hb) {
                return 0;
            }
            long va = ha ? Long.parseLong(ma.group()) : 0;
            long vb = hb ? Long.parseLong(mb.group()) : 0;
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
    }

    public static boolean isBelow(String version, String bound) {
        return compare(version, bound) < 0;
    }
}
