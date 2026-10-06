package io.renova.desktop.view;

import javafx.scene.web.WebView;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/** Renders Renova's Markdown reports (with tables) inside the app, in the current light or dark theme. */
public final class MarkdownView {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    // Raw HTML in reports is escaped: their text comes from customer projects.
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    /** Set by the app when the theme changes. */
    public static volatile boolean dark;

    private MarkdownView() {
    }

    public static WebView of(String markdown) {
        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        view.getEngine().setJavaScriptEnabled(false);
        view.getEngine().loadContent(html(markdown));
        view.setPrefHeight(640);
        return view;
    }

    static String html(String markdown) {
        String fg = dark ? "#e6edf3" : "#1f2328";
        String bg = dark ? "#0d1117" : "#ffffff";
        String muted = dark ? "#8b949e" : "#59636e";
        String border = dark ? "#30363d" : "#d1d9e0";
        String code = dark ? "#161b22" : "#f6f8fa";
        String link = dark ? "#4493f8" : "#0969da";
        return """
                <!doctype html><html><head><meta charset="utf-8"><style>
                body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; font-size: 14px; line-height: 1.55;
                       color: %s; background: %s; margin: 16px 20px; }
                h1, h2, h3 { line-height: 1.25; margin: 1.2em 0 .5em; } h1 { font-size: 1.6em; } h2 { font-size: 1.3em;
                   border-bottom: 1px solid %s; padding-bottom: .3em; } h3 { font-size: 1.1em; }
                a { color: %s; } blockquote { color: %s; border-left: 3px solid %s; margin: 0; padding-left: 12px; }
                code, pre { font-family: "JetBrains Mono", "DejaVu Sans Mono", monospace; font-size: 12.5px; background: %s;
                            border-radius: 6px; } code { padding: 1px 4px; } pre { padding: 10px 12px; overflow-x: auto; }
                pre code { padding: 0; } table { border-collapse: collapse; margin: .6em 0; }
                th, td { border: 1px solid %s; padding: 5px 9px; text-align: left; vertical-align: top; } th { background: %s; }
                </style></head><body>%s</body></html>
                """.formatted(fg, bg, border, link, muted, border, code, border, code, RENDERER.render(PARSER.parse(markdown)));
    }
}
