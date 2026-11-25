package com.docsviewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DocumentText {
    private static final int INPUT_LIMIT = 16 * 1024 * 1024;
    private static final int OFFICE_TEXT_LIMIT = 2_000_000;
    private static final int SECTION_SIZE = 24_000;
    private static final Pattern NUMBERED_PART =
            Pattern.compile("(xl/worksheets/sheet|ppt/slides/slide)([0-9]+)\\.xml");

    static String detectFormat(String name, String mime) {
        if (mime != null) {
            switch (mime) {
                case "application/pdf": return "pdf";
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
                case "application/vnd.oasis.opendocument.text": return "odt";
                case "application/vnd.oasis.opendocument.spreadsheet": return "ods";
                case "application/vnd.oasis.opendocument.presentation": return "odp";
                case "text/markdown": return "md";
                case "text/csv": return "csv";
                case "text/tab-separated-values": return "tsv";
                default: break;
            }
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isOffice(String kind) {
        return kind.matches("docx|xlsx|pptx|odt|ods|odp");
    }

    static boolean isTable(String kind) {
        return kind.matches("xlsx|ods|csv|tsv");
    }

    static boolean isPlain(String kind, String mime) {
        return kind.matches("txt|md|markdown|csv|tsv|json|xml|html|htm|log|java|kt|js|css")
                || (mime != null && mime.startsWith("text/"));
    }

    static List<String> read(InputStream input, String kind) throws Exception {
        String text = isOffice(kind) ? office(input) : plain(input);
        List<String> sections = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + SECTION_SIZE, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > start + SECTION_SIZE / 2) end = newline + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            sections.add(text.substring(start, end));
            start = end;
        }
        if (sections.isEmpty()) sections.add("No readable text found in this document.");
        return sections;
    }

    private static String plain(InputStream input) throws IOException {
        byte[] bytes = readLimited(input, INPUT_LIMIT);
        if (bytes.length >= 2) {
            int marker = (bytes[0] & 0xff) << 8 | (bytes[1] & 0xff);
            if (marker == 0xfffe || marker == 0xfeff) {
                return new String(bytes, StandardCharsets.UTF_16);
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count > limit - output.size()) {
                throw new IOException("The document exceeds the 16 MB reading limit.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String office(InputStream input) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>(DocumentText::compareParts);
        int remaining = INPUT_LIMIT;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String path = entry.getName();
                if (path.equals("word/document.xml") || path.equals("content.xml")
                        || path.equals("xl/sharedStrings.xml") || NUMBERED_PART.matcher(path).matches()) {
                    byte[] data = readLimited(zip, remaining);
                    remaining -= data.length;
                    entries.put(path, data);
                }
            }
        }

        List<String> shared = sharedStrings(entries.remove("xl/sharedStrings.xml"));
        if (entries.isEmpty()) throw new IOException("No readable document content was found.");
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = entry.getKey();
            Matcher numbered = NUMBERED_PART.matcher(path);
            if (numbered.matches()) {
                text.append(path.startsWith("ppt/") ? "Slide " : "Sheet ")
                        .append(numbered.group(2)).append("\n\n");
            }
            extract(entry.getValue(), text, shared, path);
            text.append("\n\n");
        }
        return text.toString().trim();
    }

    private static int compareParts(String a, String b) {
        Matcher left = NUMBERED_PART.matcher(a);
        Matcher right = NUMBERED_PART.matcher(b);
        if (left.matches() && right.matches() && left.group(1).equals(right.group(1))) {
            int length = Integer.compare(left.group(2).length(), right.group(2).length());
            if (length != 0) return length;
        }
        return a.compareTo(b);
    }

    private static XmlPullParser parser(byte[] xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(xml), null);
        return parser;
    }

    private static List<String> sharedStrings(byte[] xml) throws Exception {
        List<String> list = new ArrayList<>();
        if (xml == null) return list;
        XmlPullParser parser = parser(xml);
        StringBuilder string = null;
        for (int event; (event = parser.next()) != XmlPullParser.END_DOCUMENT;) {
            String tag = parser.getName();
            if (event == XmlPullParser.START_TAG && "si".equals(tag)) {
                string = new StringBuilder();
            } else if (event == XmlPullParser.START_TAG && "t".equals(tag) && string != null) {
                string.append(parser.nextText());
            } else if (event == XmlPullParser.END_TAG && "si".equals(tag) && string != null) {
                list.add(string.toString());
                string = null;
            }
        }
        return list;
    }

    private static void extract(byte[] xml, StringBuilder text, List<String> shared, String path)
            throws Exception {
        XmlPullParser parser = parser(xml);
        boolean sheet = path.startsWith("xl/");
        boolean odf = path.equals("content.xml");
        String cellType = "";
        int nextColumn = 0;
        int bodyDepth = 0;
        int paragraphDepth = 0;
        boolean cellHasValue = false;
        String formula = null;
        for (int event; (event = parser.next()) != XmlPullParser.END_DOCUMENT;) {
            String tag = parser.getName();
            if (odf && "body".equals(tag) && event == XmlPullParser.START_TAG) {
                bodyDepth = parser.getDepth();
            }
            if (odf && "body".equals(tag) && event == XmlPullParser.END_TAG) bodyDepth = 0;
            if (odf && (bodyDepth == 0 || parser.getDepth() <= bodyDepth)) continue;

            if (event == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "p":
                    case "h":
                        if (odf) paragraphDepth = parser.getDepth();
                        break;
                    case "s":
                        if (odf && paragraphDepth > 0) text.append(' ');
                        break;
                    case "c":
                        if (sheet) {
                            cellType = parser.getAttributeValue(null, "t");
                            int column = Math.max(nextColumn, columnIndex(parser.getAttributeValue(null, "r")));
                            while (nextColumn < column) {
                                text.append('\t');
                                nextColumn++;
                            }
                            cellHasValue = false;
                            formula = null;
                        }
                        break;
                    case "v":
                        if (sheet) {
                            String value = parser.nextText();
                            text.append("s".equals(cellType) ? sharedValue(shared, value) : value);
                            cellHasValue = !value.isEmpty();
                        }
                        break;
                    case "f":
                        if (sheet) formula = parser.nextText();
                        break;
                    case "t":
                        if (!odf) {
                            text.append(parser.nextText());
                            cellHasValue = true;
                        }
                        break;
                    case "tab":
                        text.append('\t');
                        break;
                    case "br":
                    case "line-break":
                        text.append('\n');
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.TEXT && odf) {
                if (paragraphDepth > 0) text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if (sheet && "c".equals(tag)) {
                    if (!cellHasValue && formula != null && !formula.isEmpty()) {
                        text.append('=').append(formula);
                    }
                    text.append('\t');
                    nextColumn++;
                } else if ("p".equals(tag) || "h".equals(tag)
                        || "row".equals(tag) || "table-row".equals(tag)) {
                    text.append('\n');
                    nextColumn = 0;
                    if (paragraphDepth == parser.getDepth()) paragraphDepth = 0;
                } else if ("table-cell".equals(tag)) {
                    text.append('\t');
                }
            }
            if (text.length() > OFFICE_TEXT_LIMIT) {
                throw new IOException("This document contains too much text for the preview.");
            }
        }
    }

    private static String sharedValue(List<String> shared, String index) {
        try {
            return shared.get(Integer.parseInt(index));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return index;
        }
    }

    private static int columnIndex(String reference) {
        if (reference == null) return 0;
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char character = reference.charAt(i);
            if (character < 'A' || character > 'Z') break;
            column = column * 26 + character - 'A' + 1;
            if (column > 16_384) return 0;
        }
        return Math.max(0, column - 1);
    }

    private DocumentText() {}
}
