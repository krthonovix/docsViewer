package com.docsviewer;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SpreadsheetPreview {
    static Preview xlsx(OfficePackage office) throws IOException {
        Preview preview = new Preview("Sheet");
        String workbookPath = OfficePackage.related(office.links(""), "officeDocument", "xl/workbook.xml");
        PartNode workbook = office.xml(workbookPath);
        Map<String, OfficePackage.Link> links = office.links(workbookPath);
        List<PartNode> shared = office.xml(OfficePackage.related(links, "sharedStrings",
                "xl/sharedStrings.xml")).children("si");
        Styles styles = new Styles(office.xml(OfficePackage.related(links, "styles", "xl/styles.xml")),
                truth(workbook.child("workbookPr").attr("date1904")));
        List<PartNode> sheets = workbook.child("sheets").children("sheet");
        if (sheets.isEmpty()) {
            for (String path : office.numbered("xl/worksheets/sheet")) {
                sheet(office.required(path), new Grid("Sheet " + (preview.sections.size() + 1), false),
                        shared, styles).append(preview);
            }
        } else {
            for (PartNode sheet : sheets) {
                String path = OfficePackage.target(links, sheet.attr("rel:id"));
                PartNode worksheet = office.xml(path);
                if (worksheet == PartNode.EMPTY) continue;
                sheet(worksheet, new Grid(sheet.attr("name"), false), shared, styles).append(preview);
            }
        }
        if (preview.sections.isEmpty()) throw new IOException("No spreadsheet worksheets could be read.");
        return preview;
    }

    private static Grid sheet(PartNode sheet, Grid grid, List<PartNode> shared, Styles styles)
            throws IOException {
        for (PartNode col : sheet.child("cols").children("col")) {
            int first = Math.max(1, Markup.integer(col.attr("min"), 1));
            int last = Math.min(16_384, Markup.integer(col.attr("max"), first));
            int width = (int) (Markup.number(col.attr("width"), 10) * 7 + 12);
            for (int i = first; i <= last; i++) grid.width(i - 1, width);
        }
        int rowNumber = 0;
        Map<Integer, String> sharedHtml = new HashMap<>();
        for (PartNode row : sheet.child("sheetData").children("row")) {
            int y = Markup.integer(row.attr("r"), rowNumber + 1) - 1;
            rowNumber = y + 1;
            int column = 0;
            for (PartNode cell : row.children("c")) {
                int x = Grid.columnIndex(cell.attr("r"));
                if (x < 0) x = column;
                column = x + 1;
                int style = Markup.integer(cell.attr("s"), 0);
                String value = cell.child("v").text();
                String formula = cell.child("f").text();
                String type = cell.attr("t");
                String html;
                String css = styles.css(style);
                if (type.equals("s")) {
                    int index = Markup.integer(value, -1);
                    html = index >= 0 && index < shared.size()
                            ? sharedHtml.computeIfAbsent(index, key -> rich(shared.get(key))) : Markup.escape(value);
                } else if (type.equals("inlineStr")) {
                    html = rich(cell.child("is"));
                } else if (type.equals("b") && !value.isEmpty()) {
                    html = value.equals("1") ? "TRUE" : "FALSE";
                } else if (!value.isEmpty() && (type.isEmpty() || type.equals("n"))) {
                    html = Markup.escape(styles.value(style, value));
                    if (!css.contains("text-align:")) css += "text-align:right;";
                } else if (value.isEmpty() && !formula.isEmpty()) {
                    html = Markup.escape("=" + formula);
                } else {
                    html = Markup.escape(value);
                }
                if (!html.isEmpty() || !css.isEmpty()) grid.put(y, x, new Grid.Cell(html, css));
            }
        }
        for (PartNode merge : sheet.child("mergeCells").children("mergeCell")) {
            String[] positions = merge.attr("ref").split(":", 2);
            if (positions.length != 2) continue;
            int left = Grid.columnIndex(positions[0]);
            int right = Grid.columnIndex(positions[1]);
            int top = rowIndex(positions[0]);
            int bottom = rowIndex(positions[1]);
            if (right >= left && left >= 0 && bottom >= top && top >= 0) {
                grid.merge(top, left, bottom, right);
            }
        }
        return grid;
    }

    private static int rowIndex(String address) {
        int start = 0;
        while (start < address.length() && !Character.isDigit(address.charAt(start))) start++;
        return Markup.integer(address.substring(start), 0) - 1;
    }

    private static String rich(PartNode node) {
        StringBuilder result = new StringBuilder();
        for (PartNode child : node.children()) {
            if (child.name.equals("t")) result.append(Markup.escape(child.text()));
            if (child.name.equals("r")) {
                String text = Markup.escape(child.child("t").text());
                result.append(Markup.wrap("span", Styles.font(child.child("rPr")), text));
            }
        }
        return result.toString();
    }

    private static boolean truth(String value) {
        return value.equals("1") || value.equalsIgnoreCase("true");
    }

    private static final class Styles {
        private final List<PartNode> formats;
        private final List<PartNode> fonts;
        private final List<PartNode> fills;
        private final Map<Integer, String> custom = new HashMap<>();
        private final Map<Integer, String> cssCache = new HashMap<>();
        private final boolean date1904;

        Styles(PartNode root, boolean date1904) {
            this.date1904 = date1904;
            formats = root.child("cellXfs").children("xf");
            fonts = root.child("fonts").children("font");
            fills = root.child("fills").children("fill");
            for (PartNode format : root.child("numFmts").children("numFmt")) {
                custom.put(Markup.integer(format.attr("numFmtId"), -1), format.attr("formatCode"));
            }
        }

        String css(int index) {
            return cssCache.computeIfAbsent(index, key -> {
                PartNode format = at(formats, key);
                int font = Markup.integer(format.attr("fontId"), 0);
                int fill = Markup.integer(format.attr("fillId"), 0);
                PartNode pattern = at(fills, fill).child("patternFill");
                PartNode background = pattern.child("fgColor");
                String css = Markup.align(format.child("alignment").attr("horizontal"));
                if (font > 0) css += font(at(fonts, font));
                if (fill > 0 && "solid".equals(pattern.attr("patternType"))) {
                    css += Markup.cssColor("background-color", rgb(background));
                    if (css.contains("background-color:") && !css.contains("color:#")) css += "color:#1f2c28;";
                }
                return css;
            });
        }

        static String font(PartNode node) {
            String css = Markup.cssColor("color", rgb(node.child("color")));
            if (on(node.child("b"))) css += "font-weight:bold;";
            if (on(node.child("i"))) css += "font-style:italic;";
            if (on(node.child("u"))) css += "text-decoration:underline;";
            if (on(node.child("strike"))) css += "text-decoration:line-through;";
            css += Markup.size("font-size", Markup.number(node.child("sz").attr("val"), Double.NaN) * 1.333);
            String name = node.child("name").attr("val");
            return css + Markup.font(name.isEmpty() ? node.child("rFont").attr("val") : name);
        }

        private static boolean on(PartNode node) {
            return node != PartNode.EMPTY && !node.attr("val").equals("0")
                    && !node.attr("val").equalsIgnoreCase("false");
        }

        private static String rgb(PartNode node) {
            String value = node.attr("rgb");
            return value.length() == 8 ? value.substring(2) : value;
        }

        private static PartNode at(List<PartNode> list, int index) {
            return index >= 0 && index < list.size() ? list.get(index) : PartNode.EMPTY;
        }

        String value(int index, String source) {
            int id = Markup.integer(at(formats, index).attr("numFmtId"), 0);
            String pattern = custom.getOrDefault(id, "");
            if (id == 0 && pattern.isEmpty()) return source;
            double value = Markup.number(source, Double.NaN);
            if (!Double.isFinite(value)) return source;
            String code = pattern.toLowerCase(Locale.ROOT);
            String simple = code.replaceAll("\"[^\"]*\"|\\\\.", "");
            boolean date = (id >= 14 && id <= 17) || id == 22 || simple.contains("yy") || simple.contains("dd");
            boolean time = (id >= 18 && id <= 22) || (id >= 45 && id <= 47) || simple.contains("h:");
            if ((date || time) && value >= 0 && value < 3_000_000) {
                int days = (int) value;
                LocalDate origin = date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 31);
                String stamp = date ? origin.plusDays(days - (!date1904 && days >= 60 ? 1 : 0)).toString() : "";
                if (time) {
                    long seconds = Math.round((value - days) * 86_400) % 86_400;
                    if (date) stamp += " ";
                    LocalTime clock = LocalTime.ofSecondOfDay(seconds);
                    stamp += String.format(Locale.ROOT, "%02d:%02d:%02d", clock.getHour(), clock.getMinute(), clock.getSecond());
                }
                return stamp;
            }
            String display = "";
            switch (id) {
                case 1: display = "0"; break;
                case 2: display = "0.00"; break;
                case 3: display = "#,##0"; break;
                case 4: display = "#,##0.00"; break;
                case 9: display = "0%"; break;
                case 10: display = "0.00%"; break;
                case 11: display = "0.00E0"; break;
                default:
                    if (pattern.matches("[0#.,%;$€£+() \\-]{1,64}")) display = pattern;
            }
            try {
                return display.isEmpty() ? source
                        : new DecimalFormat(display, DecimalFormatSymbols.getInstance()).format(value);
            } catch (IllegalArgumentException ignored) {
                return source;
            }
        }
    }

    private SpreadsheetPreview() {}
}
