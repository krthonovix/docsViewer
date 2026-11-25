package com.docsviewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WordPreview {
    private final OfficePackage office;
    private final Preview preview = new Preview("Section");
    private final Map<String, OfficePackage.Link> links;
    private final Map<String, PartNode> styles = new HashMap<>();
    private final Map<String, PartNode> numbering = new HashMap<>();
    private final Map<String, PartNode> abstractNumbering = new HashMap<>();

    static Preview read(OfficePackage office) throws IOException {
        String path = OfficePackage.related(office.links(""), "officeDocument", "word/document.xml");
        return new WordPreview(office, path).render(office.required(path).child("body"));
    }

    private WordPreview(OfficePackage office, String path) throws IOException {
        this.office = office;
        links = office.links(path);
        for (PartNode style : office.xml(OfficePackage.related(links, "styles", "word/styles.xml"))
                .children("style")) {
            styles.put(style.attr("styleId"), style);
        }
        PartNode numbers = office.xml(OfficePackage.related(links, "numbering", "word/numbering.xml"));
        for (PartNode num : numbers.children("num")) numbering.put(num.attr("numId"), num);
        for (PartNode num : numbers.children("abstractNum")) abstractNumbering.put(num.attr("abstractNumId"), num);
    }

    private Preview render(PartNode body) throws IOException {
        StringBuilder html = new StringBuilder();
        ListWriter lists = new ListWriter(html);
        for (PartNode block : body.children()) {
            if (html.length() > 100_000 && lists.levels.isEmpty()) {
                preview.add("", "<article class=\"paper\">" + html + "</article>");
                html.setLength(0);
            }
            block(block, html, lists);
            if (html.length() > 5_000_000) throw new IOException("A document section is too large to format.");
        }
        lists.close();
        if (html.length() > 0) preview.add("", "<article class=\"paper\">" + html + "</article>");
        return preview;
    }

    private void block(PartNode node, StringBuilder html, ListWriter lists) {
        if (node.name.equals("p")) {
            PartNode properties = node.child("pPr");
            String styleId = properties.child("pStyle").attr("val");
            PartNode style = styles.getOrDefault(styleId, PartNode.EMPTY);
            PartNode num = properties.child("numPr");
            if (num == PartNode.EMPTY) num = style.child("pPr").child("numPr");
            String numId = num.child("numId").attr("val");
            if (!numId.isEmpty() && !numId.equals("0")) {
                int depth = Math.max(0, Math.min(8, Markup.integer(num.child("ilvl").attr("val"), 0)));
                PartNode level = listLevel(numId, depth);
                String format = level.child("numFmt").attr("val");
                String tag = format.isEmpty() || format.equals("bullet") || format.equals("none") ? "ul" : "ol";
                lists.item(numId, depth, tag, Markup.integer(level.child("start").attr("val"), 1));
            } else {
                lists.close();
            }
            html.append(paragraph(node, properties, style, styleId));
        } else if (node.name.equals("tbl")) {
            lists.close();
            html.append(table(node));
        } else if (node.name.equals("sdt") || node.name.equals("sdtContent") || node.name.equals("customXml")) {
            for (PartNode child : node.children()) block(child, html, lists);
        }
    }

    private String paragraph(PartNode node, PartNode direct, PartNode style, String styleId) {
        String name = style.child("name").attr("val");
        if (name.isEmpty()) name = styleId;
        String tag = "p";
        String normalized = name.toLowerCase(Locale.ROOT).replace(" ", "");
        int outline = Markup.integer(direct.child("outlineLvl").attr("val"), -1);
        if (outline < 0) outline = Markup.integer(style.child("pPr").child("outlineLvl").attr("val"), -1);
        if (normalized.equals("title")) tag = "h1";
        else if (normalized.matches("heading[1-9]")) {
            tag = "h" + Math.min(6, normalized.charAt(normalized.length() - 1) - '0');
        } else if (outline >= 0 && outline < 9) tag = "h" + Math.min(6, outline + 1);
        if (normalized.contains("quote")) tag = "blockquote";
        String css = styleCss(styleId, "pPr", 0) + paragraphCss(direct) + styleCss(styleId, "rPr", 0);
        String body = inline(node);
        return Markup.wrap(tag, css, body.isEmpty() ? "&nbsp;" : body);
    }

    private String inline(PartNode node) {
        StringBuilder text = new StringBuilder();
        for (PartNode child : node.children()) {
            switch (child.name) {
                case "r": {
                    PartNode properties = child.child("rPr");
                    String css = styleCss(properties.child("rStyle").attr("val"), "rPr", 0) + runCss(properties);
                    String value = inline(child);
                    if (!value.isEmpty()) text.append(Markup.wrap("span", css, value));
                    break;
                }
                case "t": text.append(Markup.escape(child.text())); break;
                case "tab": text.append("&#9;"); break;
                case "br": case "cr": text.append("<br>"); break;
                case "noBreakHyphen": text.append("&#8209;"); break;
                case "softHyphen": text.append("&shy;"); break;
                case "drawing": case "pict": text.append(picture(child)); break;
                case "hyperlink": text.append("<span class=\"link\">").append(inline(child)).append("</span>"); break;
                case "ins": case "sdt": case "sdtContent": case "smartTag": case "fldSimple":
                    text.append(inline(child));
                    break;
                default: break;
            }
        }
        return text.toString();
    }

    private String picture(PartNode drawing) {
        String id = drawing.first("blip").attr("rel:embed");
        if (id.isEmpty()) id = drawing.first("imagedata").attr("rel:id");
        String path = OfficePackage.target(links, id);
        String url = office.imageUrl(preview, path);
        if (url.isEmpty()) return "";
        String alt = drawing.first("docPr").attr("descr");
        double width = Markup.number(drawing.first("extent").attr("cx"), Double.NaN) / 9525;
        return "<img src=\"" + url + "\" alt=\"" + Markup.escape(alt) + "\""
                + Markup.style(Markup.size("width", width)) + ">";
    }

    private String table(PartNode table) {
        List<List<WordCell>> rows = new ArrayList<>();
        Map<Integer, WordCell> vertical = new HashMap<>();
        for (PartNode row : table.children("tr")) {
            List<WordCell> cells = new ArrayList<>();
            rows.add(cells);
            Map<Integer, WordCell> active = new HashMap<>();
            int column = 0;
            for (PartNode cell : row.children("tc")) {
                PartNode properties = cell.child("tcPr");
                int span = Math.max(1, Math.min(100, Markup.integer(properties.child("gridSpan").attr("val"), 1)));
                PartNode merge = properties.child("vMerge");
                WordCell above = vertical.get(column);
                if (merge != PartNode.EMPTY && !merge.attr("val").equals("restart") && above != null) {
                    above.height++;
                    active.put(column, above);
                } else {
                    boolean heading = row.child("trPr").child("tblHeader") != PartNode.EMPTY;
                    String css = Markup.cssColor("background-color", properties.child("shd").attr("fill"));
                    StringBuilder html = new StringBuilder();
                    ListWriter list = new ListWriter(html);
                    for (PartNode block : cell.children()) block(block, html, list);
                    list.close();
                    WordCell item = new WordCell(heading ? "th" : "td", span, css, html.toString());
                    cells.add(item);
                    if (merge != PartNode.EMPTY) active.put(column, item);
                }
                column += span;
            }
            vertical = active;
        }
        StringBuilder html = new StringBuilder("<div class=\"table-scroll\"><table class=\"doc-table\"><tbody>");
        for (List<WordCell> row : rows) {
            html.append("<tr>");
            for (WordCell cell : row) {
                html.append('<').append(cell.tag).append(Markup.style(cell.css));
                if (cell.width > 1) html.append(" colspan=\"").append(cell.width).append('"');
                if (cell.height > 1) html.append(" rowspan=\"").append(cell.height).append('"');
                html.append('>').append(cell.html).append("</").append(cell.tag).append('>');
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    private PartNode listLevel(String number, int depth) {
        PartNode num = numbering.getOrDefault(number, PartNode.EMPTY);
        for (PartNode override : num.children("lvlOverride")) {
            if (Markup.integer(override.attr("ilvl"), -1) == depth && override.child("lvl") != PartNode.EMPTY) {
                return override.child("lvl");
            }
        }
        PartNode definition = abstractNumbering.getOrDefault(num.child("abstractNumId").attr("val"), PartNode.EMPTY);
        for (PartNode level : definition.children("lvl")) {
            if (Markup.integer(level.attr("ilvl"), -1) == depth) return level;
        }
        return PartNode.EMPTY;
    }

    private String styleCss(String id, String kind, int depth) {
        if (id.isEmpty() || depth >= 12) return "";
        PartNode style = styles.getOrDefault(id, PartNode.EMPTY);
        return styleCss(style.child("basedOn").attr("val"), kind, depth + 1)
                + (kind.equals("pPr") ? paragraphCss(style.child(kind)) : runCss(style.child(kind)));
    }

    private static String paragraphCss(PartNode properties) {
        String css = Markup.align(properties.child("jc").attr("val"));
        PartNode spacing = properties.child("spacing");
        PartNode indent = properties.child("ind");
        return css + twips("margin-top", spacing.attr("before")) + twips("margin-bottom", spacing.attr("after"))
                + twips("padding-inline-start", indent.attr("left"))
                + twips("padding-inline-end", indent.attr("right"))
                + twips("text-indent", indent.attr("firstLine"));
    }

    private static String twips(String property, String value) {
        return Markup.size(property, Markup.number(value, Double.NaN) / 15);
    }

    private static String runCss(PartNode properties) {
        String css = Markup.cssColor("color", properties.child("color").attr("val"));
        css += Markup.cssColor("background-color", properties.child("shd").attr("fill"));
        String highlight = properties.child("highlight").attr("val");
        if (highlight.matches("yellow|green|cyan|magenta|blue|red|black|white")) css += "background-color:" + highlight + ";";
        css += switchProperty(properties.child("b"), "font-weight", "bold", "normal");
        css += switchProperty(properties.child("i"), "font-style", "italic", "normal");
        if (properties.child("u") != PartNode.EMPTY && !properties.child("u").attr("val").equals("none")) {
            css += "text-decoration:underline;";
        }
        css += switchProperty(properties.child("strike"), "text-decoration", "line-through", "none");
        css += Markup.size("font-size", Markup.number(properties.child("sz").attr("val"), Double.NaN) * 2 / 3);
        String vertical = properties.child("vertAlign").attr("val");
        if (vertical.equals("superscript")) css += "vertical-align:super;font-size:0.72em;";
        if (vertical.equals("subscript")) css += "vertical-align:sub;font-size:0.72em;";
        return css + Markup.font(properties.child("rFonts").attr("ascii"));
    }

    private static String switchProperty(PartNode node, String name, String on, String off) {
        if (node == PartNode.EMPTY) return "";
        String value = node.attr("val");
        return name + ":" + (value.equals("0") || value.equalsIgnoreCase("false") || value.equals("off") ? off : on) + ";";
    }

    private static final class WordCell {
        final String tag;
        final int width;
        final String css;
        final String html;
        int height = 1;

        WordCell(String tag, int width, String css, String html) {
            this.tag = tag;
            this.width = width;
            this.css = css;
            this.html = html;
        }
    }

    private static final class ListWriter {
        final List<String> levels = new ArrayList<>();
        private final StringBuilder html;
        private String id = "";

        ListWriter(StringBuilder html) {
            this.html = html;
        }

        void item(String identifier, int depth, String tag, int start) {
            if (!identifier.equals(id)) {
                close();
                id = identifier;
            }
            depth = Math.min(depth, levels.size());
            while (levels.size() > depth + 1) closeLast();
            if (depth == levels.size()) {
                levels.add(tag);
                html.append('<').append(tag);
                if (tag.equals("ol") && start > 1) html.append(" start=\"").append(start).append('"');
                html.append('>');
            } else {
                html.append("</li>");
            }
            html.append("<li>");
        }

        void close() {
            while (!levels.isEmpty()) closeLast();
        }

        private void closeLast() {
            html.append("</li></").append(levels.remove(levels.size() - 1)).append('>');
        }
    }
}
