package com.docsviewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

final class Grid {
    private static final int ROWS_PER_SECTION = 100;
    private static final int COLUMNS_PER_SECTION = 80;
    private static final int MAX_CELLS = 160_000;
    private final TreeMap<Integer, Map<Integer, Cell>> rows = new TreeMap<>();
    private final TreeMap<Integer, Integer> widths = new TreeMap<>();
    private final String name;
    private final boolean header;
    private int cells;

    Grid(String name, boolean header) {
        this.name = name;
        this.header = header;
    }

    void put(int row, int column, Cell cell) throws IOException {
        if (row < 0 || row >= 1_048_576 || column < 0 || column >= 16_384) return;
        if (++cells > MAX_CELLS) throw new IOException("There are too many populated cells to preview.");
        rows.computeIfAbsent(row, key -> new TreeMap<>()).put(column, cell);
    }

    void width(int column, int pixels) {
        if (column >= 0 && column < 16_384) widths.put(column, Math.max(52, Math.min(400, pixels)));
    }

    void merge(int top, int left, int bottom, int right) throws IOException {
        Map<Integer, Cell> row = rows.get(top);
        Cell cell = row == null ? null : row.get(left);
        if (cell == null) cell = new Cell("", "");
        put(top, left, new Cell(cell.html, cell.css,
                Math.max(1, right - left + 1), Math.max(1, bottom - top + 1)));
    }

    void append(Preview preview) throws IOException {
        if (rows.isEmpty()) {
            preview.add(name, "<p>This sheet is empty.</p>");
            return;
        }
        TreeSet<Integer> rowBands = new TreeSet<>();
        TreeSet<Integer> colBands = new TreeSet<>();
        int firstColumn = 16_384;
        for (Map.Entry<Integer, Map<Integer, Cell>> row : rows.entrySet()) {
            rowBands.add(row.getKey() / ROWS_PER_SECTION);
            for (int column : row.getValue().keySet()) {
                colBands.add(column / COLUMNS_PER_SECTION);
                firstColumn = Math.min(column, firstColumn);
            }
        }
        int previousRow = Math.max(0, rows.firstKey() - 12);
        boolean describe = rowBands.size() > 1 || colBands.size() > 1;
        for (int rowBand : rowBands) {
            int top = rowBand * ROWS_PER_SECTION;
            int bottom = top + ROWS_PER_SECTION;
            Map<Integer, Map<Integer, Cell>> area = rows.subMap(top, true, bottom, false);
            TreeMap<Integer, Integer> lastColumn = new TreeMap<>();
            Map<Integer, Integer> mergedBottom = new HashMap<>();
            for (Map.Entry<Integer, Map<Integer, Cell>> row : area.entrySet()) {
                for (Map.Entry<Integer, Cell> cell : row.getValue().entrySet()) {
                    int column = cell.getKey();
                    int band = column / COLUMNS_PER_SECTION;
                    int end = Math.min((band + 1) * COLUMNS_PER_SECTION - 1, column + cell.getValue().colspan - 1);
                    lastColumn.merge(band, end, Math::max);
                    int rowEnd = Math.min(bottom - 1, row.getKey() + cell.getValue().rowspan - 1);
                    mergedBottom.merge(band, rowEnd, Math::max);
                }
            }
            if (lastColumn.isEmpty()) continue;
            for (Map.Entry<Integer, Integer> band : lastColumn.entrySet()) {
                int left = band.getKey() * COLUMNS_PER_SECTION;
                int right = band.getValue();
                if (right - Math.max(left, firstColumn) > 32) left = Math.max(left, firstColumn - 6);
                int first = Math.max(top, previousRow);
                int last = Math.max(rows.floorKey(bottom - 1), mergedBottom.get(band.getKey()));
                String title = name;
                if (describe) {
                    String range = columnName(left) + (first + 1) + "–" + columnName(right) + (last + 1);
                    title = name.isEmpty() ? range : name + " · " + range;
                }
                preview.add(title, render(first, last, left, right));
            }
            previousRow = bottom;
        }
    }

    private String render(int top, int bottom, int left, int right) {
        StringBuilder html = new StringBuilder("<div class=\"grid\"><table><colgroup><col class=\"index\">");
        for (int x = left; x <= right; x++) {
            Integer width = widths.get(x);
            html.append(width == null ? "<col>" : "<col style=\"width:" + width + "px\">");
        }
        html.append("</colgroup><thead><tr><th class=\"index\">&nbsp;</th>");
        for (int x = left; x <= right; x++) html.append("<th scope=\"col\">").append(columnName(x)).append("</th>");
        html.append("</tr></thead><tbody>");
        Map<Integer, Integer> verticalMerge = new HashMap<>();
        for (int y = top; y <= bottom; y++) {
            Map<Integer, Cell> row = rows.get(y);
            if (row == null && y + 10 < bottom) {
                Integer next = rows.higherKey(y);
                if (next != null && next - y > 20 && verticalMerge.isEmpty()) {
                    html.append("<tr class=\"gap\"><th class=\"index\">…</th><td colspan=\"")
                            .append(right - left + 1).append("\">Rows ").append(y + 1)
                            .append("–").append(next).append("</td></tr>");
                    y = next - 1;
                    continue;
                }
            }
            html.append(y == 0 && header ? "<tr class=\"file-header\">" : "<tr>")
                    .append("<th scope=\"row\" class=\"index\">").append(y + 1).append("</th>");
            for (int x = left; x <= right; x++) {
                if (verticalMerge.getOrDefault(x, -1) >= y) continue;
                verticalMerge.remove(x);
                Cell cell = row == null ? null : row.get(x);
                if (cell == null) {
                    html.append("<td></td>");
                    continue;
                }
                int colspan = Math.min(cell.colspan, right - x + 1);
                int rowspan = Math.min(cell.rowspan, bottom - y + 1);
                if (rowspan > 1) {
                    for (int span = x; span < x + colspan; span++) verticalMerge.put(span, y + rowspan - 1);
                }
                html.append("<td");
                if (colspan > 1) html.append(" colspan=\"").append(colspan).append('"');
                if (rowspan > 1) html.append(" rowspan=\"").append(rowspan).append('"');
                html.append(Markup.style(cell.css)).append('>').append(cell.html).append("</td>");
                x += colspan - 1;
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table></div>").toString();
    }

    static Grid delimited(String text, char separator) throws IOException {
        Grid grid = new Grid("", true);
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int number = 0;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            pending = true;
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == separator || c == '\n' || c == '\r') {
                row.add(cell.toString());
                cell.setLength(0);
                if (row.size() > 16_384) throw new IOException("The file has more than 16,384 columns.");
                if (c != separator) {
                    addDelimitedRow(grid, row, number++);
                    row.clear();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    pending = false;
                }
            } else {
                cell.append(c);
            }
        }
        if (pending || !row.isEmpty()) {
            row.add(cell.toString());
            addDelimitedRow(grid, row, number);
        }
        return grid;
    }

    private static void addDelimitedRow(Grid grid, List<String> values, int row) throws IOException {
        for (int col = 0; col < values.size(); col++) {
            grid.put(row, col, new Cell(Markup.escape(values.get(col)), ""));
        }
    }

    static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) name.append((char) ('A' + (n - 1) % 26));
        return name.reverse().toString();
    }

    static int columnIndex(String address) {
        int index = 0;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c == '$') continue;
            if (c < 'A' || c > 'Z') break;
            index = index * 26 + c - 'A' + 1;
            if (index > 16_384) return -1;
        }
        return index - 1;
    }

    static final class Cell {
        final String html;
        final String css;
        final int colspan;
        final int rowspan;

        Cell(String html, String css) {
            this(html, css, 1, 1);
        }

        Cell(String html, String css, int colspan, int rowspan) {
            this.html = html;
            this.css = css;
            this.colspan = colspan;
            this.rowspan = rowspan;
        }
    }
}
