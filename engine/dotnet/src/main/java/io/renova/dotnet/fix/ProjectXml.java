package io.renova.dotnet.fix;

import io.renova.dotnet.Tfm;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Edits to an SDK-style project file as text, so its layout and comments stay as they are. */
public final class ProjectXml {

    private static final Pattern FRAMEWORKS = Pattern.compile("<(TargetFrameworks?)>([^<]*)</TargetFrameworks?>");

    private ProjectXml() {
    }

    /**
     * Moves every target framework older than modern .NET {@code target} to it. A project tied to Windows
     * (WPF, Windows Forms, or already {@code -windows}) stays tied to it; .NET Standard targets are kept.
     */
    public static String setTargetFramework(String xml, String target) {
        boolean windows = xml.matches("(?s).*<Use(WPF|WindowsForms)>\\s*true\\s*</Use(WPF|WindowsForms)>.*");
        Matcher m = FRAMEWORKS.matcher(xml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Set<String> moved = new LinkedHashSet<>();
            for (String tfm : m.group(2).split(";")) {
                if (tfm.isBlank()) {
                    continue;
                }
                String platform = Tfm.platform(tfm).isEmpty() && windows ? "-windows" : Tfm.platform(tfm);
                moved.add(Tfm.below(tfm.strip(), target) ? Tfm.modern(target, platform) : tfm.strip());
            }
            String element = moved.size() > 1 ? "TargetFrameworks" : "TargetFramework";
            m.appendReplacement(out, Matcher.quoteReplacement("<" + element + ">" + String.join(";", moved) + "</" + element + ">"));
        }
        return m.appendTail(out).toString();
    }

    /** Sets the version of a package the file references, in either way of writing it. */
    public static String setPackageVersion(String xml, String id, String version) {
        String quoted = Pattern.quote(id);
        String attribute = Pattern.compile("(?i)(<PackageReference\\s+(?:Include|Update)=\"" + quoted + "\"\\s+Version=\")[^\"]*(\")")
                .matcher(xml).replaceAll("$1" + Matcher.quoteReplacement(version) + "$2");
        return Pattern.compile("(?is)(<PackageReference\\s+(?:Include|Update)=\"" + quoted + "\"\\s*>\\s*<Version>)[^<]*(</Version>)")
                .matcher(attribute).replaceAll("$1" + Matcher.quoteReplacement(version) + "$2");
    }

    public static boolean hasPackage(String xml, String id) {
        return Pattern.compile("(?i)<PackageReference\\s+(?:Include|Update)=\"" + Pattern.quote(id) + "\"").matcher(xml).find();
    }

    /** Adds a package beside the others, or in a group of its own; a file that has it is returned unchanged. */
    public static String addPackage(String xml, String id, String version) {
        if (hasPackage(xml, id)) {
            return xml;
        }
        String newline = xml.contains("\r\n") ? "\r\n" : "\n";
        Matcher existing = Pattern.compile("(?m)^([ \\t]*)<PackageReference\\b[^\\n]*\\R").matcher(xml);
        String reference = "<PackageReference Include=\"" + id + "\"" + (version == null ? "" : " Version=\"" + version + "\"") + " />";
        int last = -1;
        String indent = "    ";
        while (existing.find()) {
            last = existing.end();
            indent = existing.group(1);
        }
        if (last >= 0) {
            // After the last one, unless that one spans several lines: then after its closing tag.
            int close = xml.indexOf("</PackageReference>", last);
            int nextOpen = xml.indexOf("<PackageReference", last);
            int group = xml.indexOf("</ItemGroup>", last);
            if (close >= 0 && close < group && (nextOpen < 0 || close < nextOpen)) {
                last = xml.indexOf('\n', close) + 1;
            }
            return xml.substring(0, last) + indent + reference + newline + xml.substring(last);
        }
        int end = xml.lastIndexOf("</Project>");
        if (end < 0) {
            return xml;
        }
        return xml.substring(0, end) + "  <ItemGroup>" + newline + "    " + reference + newline + "  </ItemGroup>" + newline
                + newline + xml.substring(end);
    }

    /** Removes a package reference, whichever way it is written. */
    public static String removePackage(String xml, String id) {
        String quoted = Pattern.quote(id);
        String single = Pattern.compile("(?im)^[ \\t]*<PackageReference\\s+(?:Include|Update)=\"" + quoted + "\"[^>]*/>[ \\t]*\\R?")
                .matcher(xml).replaceAll("");
        return Pattern.compile("(?ims)^[ \\t]*<PackageReference\\s+(?:Include|Update)=\"" + quoted + "\"[^>]*>.*?</PackageReference>[ \\t]*\\R?")
                .matcher(single).replaceAll("");
    }

    /** Sets a property where the file has it, or adds it to the first property group. */
    public static String setProperty(String xml, String name, String value) {
        Pattern existing = Pattern.compile("<" + Pattern.quote(name) + ">[^<]*</" + Pattern.quote(name) + ">");
        String element = "<" + name + ">" + value + "</" + name + ">";
        if (existing.matcher(xml).find()) {
            return existing.matcher(xml).replaceAll(Matcher.quoteReplacement(element));
        }
        Matcher group = Pattern.compile("(?m)^([ \\t]*)</PropertyGroup>").matcher(xml);
        if (!group.find()) {
            return xml;
        }
        String newline = xml.contains("\r\n") ? "\r\n" : "\n";
        return xml.substring(0, group.start()) + group.group(1) + "  " + element + newline + xml.substring(group.start());
    }
}
