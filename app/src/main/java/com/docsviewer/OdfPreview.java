package com.docsviewer;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class OdfPreview {
    private final OfficePackage office;
    private final Preview preview;
    private final Map<String, PartNode> styles = new HashMap<>();
    private final Map<String, PartNode> layouts = new HashMap<>();
    private final Map<String, PartNode> masters = new HashMap<>();
    private final Map<String, String> cachedStyles = new HashMap<>();

    static Preview read(OfficePackage office, String kind) throws IOException {
        OdfPreview renderer = new OdfPreview(office, kind);
        PartNode root = office.required("content.xml");
        renderer.addStyles(office.xml("styles.xml"));
        renderer.addStyles(root);
        PartNode body = root.child("body");
        switch (kind) {
            case "ods": renderer.sheets(body.child("spreadsheet")); break;
            case "odp": renderer.slides(body.child("presentation")); break;
            default: renderer.document(body.child("text"));
        }
        return renderer.preview;
    }

    private OdfPreview(OfficePackage office, String kind) {
        this.office = office;
        preview = new Preview(kind.equals("odp") ? "Slide" : kind.equals("ods") ? "Sheet" : "Section");
    }

    private void addStyles(PartNode root) {
        for (PartNode style : root.descendants("style")) styles.put(style.attr("name"), style);
        for (PartNode layout : root.descendants("page-layout")) layouts.put(layout.attr("name"), layout);
        for (PartNode master : root.descendants("master-page")) masters.put(master.attr("name"), master);
        for (PartNode style : root.descendants("list-style")) styles.put(style.attr("name"), style);
    }

    private void document(PartNode body) throws IOException {
        StringBuilder section = new StringBuilder();
        for (PartNode block : body.children()) {
            section.append(flow(block, ""));
            if (section.length() > 100_000) {
                preview.add("", "<article class=\"paper\">" + section + "</article>");
                section.setLength(0);
            }
        }
        if (section.length() != 0) preview.add("", "<article class=\"paper\">" + section + "</article>");
    }

    private String flow(PartNode node, String listStyle) {
        String styleName = node.attr("style-name");
        String css = css(styleName);
        switch (node.name) {
            case "h":
                int level = Math.max(1, Math.min(6, Markup.integer(node.attr("outline-level"), 1)));
                return Markup.wrap("h" + level, css, childFlow(node, listStyle));
            case "p": case "span":
                return Markup.wrap(node.name, css, childFlow(node, listStyle));
            case "list": {
                String name = styleName.isEmpty() ? listStyle : styleName;
                PartNode style = styles.getOrDefault(name, PartNode.EMPTY);
                boolean numbered = style.child("list-level-style-number") != PartNode.EMPTY;
                return Markup.wrap(numbered ? "ol" : "ul", "", childFlow(node, name));
            }
            case "list-item": return "<li>" + childFlow(node, listStyle) + "</li>";
            case "list-header": return "<li class=\"list-heading\">" + childFlow(node, listStyle) + "</li>";
            case "line-break": return "<br>";
            case "tab": return "&#9;";
            case "soft-page-break": return "";
            case "s":
                int spaces = Math.max(1, Math.min(1000, Markup.integer(node.attr("c"), 1)));
                StringBuilder repeated = new StringBuilder();
                for (int i = 0; i < spaces; i++) repeated.append("&nbsp;");
                return repeated.toString();
            case "a": return "<span class=\"link\">" + childFlow(node, listStyle) + "</span>";
            case "section": case "text-box": return childFlow(node, listStyle);
            case "frame": return frame(node);
            case "image": return office.image(preview, OfficePackage.resolve("content.xml", node.attr("href")), "");
            case "table": return table(node);
            case "#text": return Markup.escape(node.value);
            default: return "";
        }
    }

    private String childFlow(PartNode node, String listStyle) {
        StringBuilder html = new StringBuilder();
        for (PartNode child : node.children()) {
            if (child.name.equals("#text") && child.value.trim().isEmpty()
                    && node.name.matches("section|list|list-item|text-box|table-cell")) continue;
            html.append(flow(child, listStyle));
        }
        return html.toString();
    }

    private String frame(PartNode node) {
        PartNode image = node.child("image");
        if (image == PartNode.EMPTY) return childFlow(node, "");
        String url = office.imageUrl(preview, OfficePackage.resolve("content.xml", image.attr("href")));
        if (url.isEmpty()) return "";
        double width = Markup.length(node.attr("width"), Double.NaN);
        return "<img src=\"" + url + "\" alt=\"" + Markup.escape(node.child("desc").text()) + "\""
                + Markup.style(Markup.size("width", width)) + ">";
    }

    private String table(PartNode table) {
        StringBuilder html = new StringBuilder("<div class=\"table-scroll\"><table class=\"doc-table\"><tbody>");
        appendTableRows(table, false, html);
        return html.append("</tbody></table></div>").toString();
    }

    private void appendTableRows(PartNode parent, boolean header, StringBuilder html) {
        for (PartNode row : parent.children()) {
            if (row.name.equals("table-header-rows") || row.name.equals("table-rows") || row.name.equals("table-row-group")) {
                appendTableRows(row, header || row.name.equals("table-header-rows"), html);
                continue;
            }
            if (!row.name.equals("table-row")) continue;
            StringBuilder cells = new StringBuilder("<tr>");
            for (PartNode cell : row.children("table-cell")) {
                int span = Math.min(100, repeat(cell, "number-columns-spanned"));
                int rows = Math.min(1000, repeat(cell, "number-rows-spanned"));
                String tag = header ? "th" : "td";
                String element = "<" + tag + " colspan=\"" + span + "\" rowspan=\"" + rows + "\""
                        + Markup.style(css(cell.attr("style-name"))) + ">" + childFlow(cell, "") + "</" + tag + ">";
                for (int i = 0; i < Math.min(100, repeat(cell, "number-columns-repeated")); i++) {
                    if (cells.length() > 720_000) break;
                    cells.append(element);
                }
            }
            cells.append("</tr>");
            for (int i = 0; i < Math.min(200, repeat(row, "number-rows-repeated")); i++) {
                if (html.length() > 1_500_000) break;
                html.append(cells);
            }
        }
    }

    private void sheets(PartNode workbook) throws IOException {
        for (PartNode sheet : workbook.children("table")) {
            Grid grid = new Grid(sheet.attr("name"), false);
            columnWidths(sheet, grid, 0);
            sheetRows(sheet, grid, 0);
            grid.append(preview);
        }
    }

    private int columnWidths(PartNode node, Grid grid, int column) {
        for (PartNode child : node.children()) {
            if (child.name.equals("table-column")) {
                PartNode style = styles.getOrDefault(child.attr("style-name"), PartNode.EMPTY);
                double width = Markup.length(style.child("table-column-properties").attr("column-width"), Double.NaN);
                int count = Math.min(16_384 - column, repeat(child, "number-columns-repeated"));
                if (Double.isFinite(width)) {
                    for (int i = 0; i < count; i++) grid.width(column + i, (int) width);
                }
                column += count;
            } else if (child.name.matches("table-columns|table-header-columns|table-column-group")) {
                column = columnWidths(child, grid, column);
            }
        }
        return column;
    }

    private int sheetRows(PartNode parent, Grid grid, int rowNumber) throws IOException {
        for (PartNode row : parent.children()) {
            if (row.name.matches("table-header-rows|table-rows|table-row-group")) {
                rowNumber = sheetRows(row, grid, rowNumber);
                continue;
            }
            if (!row.name.equals("table-row") || rowNumber >= 1_048_576) continue;
            Map<Integer, Grid.Cell> cells = new HashMap<>();
            int column = 0;
            for (PartNode cell : row.children()) {
                if (!cell.name.equals("table-cell") && !cell.name.equals("covered-table-cell")) continue;
                int count = Math.min(16_384 - column, repeat(cell, "number-columns-repeated"));
                if (!cell.name.equals("covered-table-cell")) {
                    String value = childFlow(cell, "").trim();
                    if (value.isEmpty()) value = Markup.escape(scalar(cell));
                    String css = css(cell.attr("style-name"));
                    if (!value.isEmpty() || (!css.isEmpty() && count <= 40)) {
                        Grid.Cell rendered = new Grid.Cell(value, css, repeat(cell, "number-columns-spanned"),
                                repeat(cell, "number-rows-spanned"));
                        for (int i = 0; i < count; i++) cells.put(column + i, rendered);
                    }
                }
                column += count;
            }
            int count = Math.min(1_048_576 - rowNumber, repeat(row, "number-rows-repeated"));
            if (!cells.isEmpty()) {
                for (int i = 0; i < count; i++) {
                    for (Map.Entry<Integer, Grid.Cell> cell : cells.entrySet()) {
                        grid.put(rowNumber + i, cell.getKey(), cell.getValue());
                    }
                }
            }
            rowNumber += count;
        }
        return rowNumber;
    }

    private static String scalar(PartNode cell) {
        switch (cell.attr("value-type")) {
            case "date": return cell.attr("date-value");
            case "time": return cell.attr("time-value");
            case "boolean": return cell.attr("boolean-value");
            case "string": return cell.attr("string-value");
            case "float": case "percentage": case "currency": return cell.attr("value");
            default: return cell.attr("formula");
        }
    }

    private void slides(PartNode presentation) throws IOException {
        for (PartNode page : presentation.children("page")) {
            PartNode master = masters.getOrDefault(page.attr("master-page-name"), PartNode.EMPTY);
            PartNode layout = layouts.getOrDefault(master.attr("page-layout-name"), PartNode.EMPTY)
                    .child("page-layout-properties");
            double width = Math.max(1, Markup.length(layout.attr("page-width"), 960));
            double height = Math.max(1, Markup.length(layout.attr("page-height"), 540));
            SlideCanvas canvas = new SlideCanvas(width, height);
            String background = background(page.attr("style-name"));
            if (background.isEmpty()) background = background(master.attr("style-name"));
            drawOdf(master, canvas, true);
            drawOdf(page, canvas, false);
            String name = page.attr("name");
            if (name.isEmpty()) name = "Slide " + (preview.sections.size() + 1);
            preview.add(name, canvas.html(background, name));
        }
    }

    private void drawOdf(PartNode page, SlideCanvas canvas, boolean master) {
        for (PartNode shape : page.children()) {
            if (shape.name.equals("g")) {
                drawOdf(shape, canvas, master);
                continue;
            }
            if (!shape.name.matches("frame|rect|ellipse|circle|custom-shape|line")) continue;
            if (master && !shape.attr("class").isEmpty()) continue;
            double x = Markup.length(shape.attr("x"), 0);
            double y = Markup.length(shape.attr("y"), 0);
            double width = Markup.length(shape.attr("width"), 0);
            double height = Markup.length(shape.attr("height"), 0);
            String style = shape.attr("style-name");
            if (shape.name.equals("line")) {
                x = Markup.length(shape.attr("x1"), 0);
                y = Markup.length(shape.attr("y1"), 0);
                SlideCanvas.Box line = new SlideCanvas.Box(x, y, Markup.length(shape.attr("x2"), x) - x,
                        Markup.length(shape.attr("y2"), y) - y, 0);
                canvas.line(line, Markup.color(styles.getOrDefault(style, PartNode.EMPTY)
                        .child("graphic-properties").attr("stroke-color")));
                continue;
            }
            SlideCanvas.Box box = new SlideCanvas.Box(x, y, width, height, 0);
            PartNode image = shape.child("image");
            if (image != PartNode.EMPTY) {
                canvas.image(box, office.imageUrl(preview, OfficePackage.resolve("content.xml", image.attr("href"))));
                continue;
            }
            String css = css(style);
            if (shape.name.equals("ellipse") || shape.name.equals("circle")) css += "border-radius:50%;";
            String shapeType = shape.child("enhanced-geometry").attr("type");
            if (shapeType.equals("diamond")) css += "clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%);";
            if (shapeType.equals("isosceles-triangle")) css += "clip-path:polygon(50% 0,100% 100%,0 100%);";
            canvas.box(box, css, childFlow(shape, ""));
        }
    }

    private String background(String style) {
        PartNode properties = styles.getOrDefault(style, PartNode.EMPTY).child("drawing-page-properties");
        return Markup.color(properties.attr("fill-color"));
    }

    private String css(String name) {
        return name.isEmpty() ? "" : cachedStyles.computeIfAbsent(name, key -> styleCss(key, 0));
    }

    private String styleCss(String name, int depth) {
        if (name.isEmpty() || depth >= 12) return "";
        PartNode style = styles.getOrDefault(name, PartNode.EMPTY);
        String css = styleCss(style.attr("parent-style-name"), depth + 1);
        PartNode text = style.child("text-properties");
        PartNode paragraph = style.child("paragraph-properties");
        PartNode cell = style.child("table-cell-properties");
        PartNode shape = style.child("graphic-properties");
        css += Markup.cssColor("color", text.attr("color")) + Markup.cssColor("background-color", text.attr("background-color"))
                + Markup.cssColor("background-color", cell.attr("background-color")) + Markup.align(paragraph.attr("text-align"));
        if (!shape.attr("fill").equals("none")) css += Markup.cssColor("background-color", shape.attr("fill-color"));
        String stroke = Markup.color(shape.attr("stroke-color"));
        if (!stroke.isEmpty() && !shape.attr("stroke").equals("none")) css += "border:1px solid " + stroke + ";";
        if (text.attr("font-weight").matches("bold|normal|[1-9]00")) css += "font-weight:" + text.attr("font-weight") + ";";
        if (text.attr("font-style").matches("italic|oblique|normal")) css += "font-style:" + text.attr("font-style") + ";";
        css += Markup.font(text.attr("font-name"));
        css += Markup.size("font-size", Markup.length(text.attr("font-size"), Double.NaN));
        css += Markup.size("margin-top", Markup.length(paragraph.attr("margin-top"), Double.NaN));
        css += Markup.size("margin-bottom", Markup.length(paragraph.attr("margin-bottom"), Double.NaN));
        css += Markup.size("padding-inline-start", Markup.length(paragraph.attr("margin-left"), Double.NaN));
        css += Markup.size("text-indent", Markup.length(paragraph.attr("text-indent"), Double.NaN));
        if (!text.attr("text-underline-style").matches("|none")) css += "text-decoration:underline;";
        if (!text.attr("text-line-through-style").matches("|none")) css += "text-decoration:line-through;";
        return css;
    }

    private static int repeat(PartNode node, String name) {
        return Math.max(1, Math.min(1_048_576, Markup.integer(node.attr(name), 1)));
    }
}
