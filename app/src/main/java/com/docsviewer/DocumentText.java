package com.docsviewer;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DocumentText {
    private static final int INPUT_LIMIT = 16 * 1024 * 1024;
    private static final int SECTION_SIZE = 32_000;
    private static final Pattern SOURCE_TOKEN = Pattern.compile(
            "(?<comment><!--.*?-->|/\\*.*?\\*/|//[^\\r\\n]*)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
            + "|(?<tag></?[\\w:.-]+|/?>)"
            + "|(?<keyword>\\b(?:class|interface|enum|record|public|private|protected|"
            + "abstract|static|final|new|return|throw|try|catch|finally|for|while|if|else|switch|case|"
            + "import|package|fun|val|var|let|const|function|def|true|false|null|undefined|async|await)\\b)"
            + "|(?<number>\\b\\d+(?:\\.\\d+)?\\b)", Pattern.DOTALL);

    static String detectFormat(String name, String mime) {
        if (mime != null) {
            switch (mime.split(";", 2)[0].toLowerCase(Locale.ROOT)) {
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
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.matches("pdf|docx|xlsx|pptx|odt|ods|odp|txt|md|markdown|csv|tsv|json|xml|html|htm|log|java|kt|js|css")) {
            return extension;
        }
        if (mime != null) {
            if (mime.equals("application/json")) return "json";
            if (mime.equals("application/xml") || mime.equals("text/xml")) return "xml";
            if (mime.equals("text/html")) return "html";
            if (mime.startsWith("text/")) return extension.isEmpty() ? "txt" : extension;
        }
        return "";
    }

    static Preview read(InputStream input, String kind) throws IOException {
        Preview result;
        switch (kind) {
            case "docx": result = WordPreview.read(OfficePackage.read(input)); break;
            case "xlsx": result = SpreadsheetPreview.xlsx(OfficePackage.read(input)); break;
            case "pptx": result = SlidePreview.pptx(OfficePackage.read(input)); break;
            case "odt": case "ods": case "odp":
                result = OdfPreview.read(OfficePackage.read(input), kind);
                break;
            default: result = readText(plain(input), kind); break;
        }
        if (result.sections.isEmpty()) result.add("", "<p>No readable content found.</p>");
        return result;
    }

    private static Preview readText(String source, String format) throws IOException {
        Preview preview = new Preview("Section");
        switch (format) {
            case "md": case "markdown":
                if (source.length() > 1_500_000) throw new IOException("This Markdown file is too large to format.");
                preview.add("", "<main class=\"reading\">" + Markdown.render(source) + "</main>");
                break;
            case "html": case "htm":
                if (source.length() > 2_000_000) throw new IOException("This HTML file is too large to format.");
                preview.add("", "<main class=\"reading\">" + html(source) + "</main>");
                break;
            case "csv": case "tsv":
                Grid.delimited(source, format.equals("csv") ? ',' : '\t').append(preview);
                break;
            case "json":
                sections(preview, prettyJson(source), true, true);
                break;
            case "xml":
                sections(preview, prettyXml(source), true, true);
                break;
            case "java": case "kt": case "js": case "css":
                sections(preview, source, true, true);
                break;
            case "log":
                sections(preview, source, true, false);
                break;
            default:
                sections(preview, source, false, false);
        }
        return preview;
    }

    private static String html(String source) {
        Document parsed = Jsoup.parse(source);
        Safelist safelist = Safelist.relaxed()
                .addTags("article", "section", "main", "header", "footer", "figure", "figcaption",
                        "details", "summary", "mark", "kbd", "samp", "time", "colgroup", "col")
                .addAttributes(":all", "style", "class", "id", "dir", "lang")
                .addAttributes("ol", "start", "reversed")
                .addAttributes("img", "alt", "width", "height")
                .addProtocols("img", "src", "data");
        Document clean = new Cleaner(safelist).clean(parsed);
        for (org.jsoup.nodes.Element image : clean.select("img[src]")) {
            if (!image.attr("src").matches("(?is)data:image/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+")) {
                image.removeAttr("src");
            }
        }
        clean.outputSettings().prettyPrint(false);
        StringBuilder styles = new StringBuilder();
        for (org.jsoup.nodes.Element style : parsed.select("head style")) {
            styles.append("<style>").append(style.data().replace("<", "\\3c ")).append("</style>");
        }
        return styles.append(clean.body().html()).toString();
    }

    private static String prettyXml(String source) {
        if (source.length() > 200_000 || !source.contains("><")) return source;
        Document document = Jsoup.parse(source, "", org.jsoup.parser.Parser.xmlParser());
        document.outputSettings().indentAmount(2).prettyPrint(true);
        return document.outerHtml();
    }

    private static String prettyJson(String source) {
        // Keep very large files and malformed JSON readable without changing their contents.
        if (source.length() > 200_000 || nesting(source) > 80) return source;
        try {
            JSONTokener tokener = new JSONTokener(source);
            Object value = tokener.nextValue();
            if (tokener.nextClean() != 0) return source;
            if (value instanceof JSONObject) return ((JSONObject) value).toString(2);
            if (value instanceof JSONArray) return ((JSONArray) value).toString(2);
        } catch (JSONException ignored) {
            // Fall back to the original source.
        }
        return source;
    }

    private static int nesting(String source) {
        int depth = 0;
        int maximum = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{' || c == '[') {
                maximum = Math.max(maximum, ++depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return maximum;
    }

    private static void sections(Preview preview, String text, boolean source, boolean highlight)
            throws IOException {
        int line = 1;
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + SECTION_SIZE, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > start) end = newline + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String section = text.substring(start, end);
            if (source) {
                StringBuilder html = new StringBuilder("<div class=\"source\"><table><tbody>");
                String[] lines = section.split("\\r\\n|\\n|\\r", -1);
                int count = lines.length;
                if (end < text.length() && section.endsWith("\n")) count--;
                for (int i = 0; i < count; i++) {
                    html.append("<tr><th aria-hidden=\"true\">").append(line++)
                            .append("</th><td>").append(highlight ? highlight(lines[i]) : Markup.escape(lines[i]))
                            .append("</td></tr>");
                }
                preview.add("", html.append("</tbody></table></div>").toString());
            } else {
                preview.add("", "<div class=\"plain\">" + Markup.escape(section) + "</div>");
            }
            start = end;
        }
    }

    private static String highlight(String line) {
        if (line.length() > 4000) return Markup.escape(line);
        Matcher matcher = SOURCE_TOKEN.matcher(line);
        StringBuilder html = new StringBuilder();
        int start = 0;
        while (matcher.find()) {
            String kind = "number";
            for (String candidate : new String[]{"comment", "string", "tag", "keyword"}) {
                if (matcher.group(candidate) != null) {
                    kind = candidate;
                    break;
                }
            }
            html.append(Markup.escape(line.substring(start, matcher.start())))
                    .append("<span class=\"tok-").append(kind).append("\">")
                    .append(Markup.escape(matcher.group())).append("</span>");
            start = matcher.end();
        }
        return html.append(Markup.escape(line.substring(start))).toString();
    }

    private static String plain(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[16 * 1024];
        for (int count; (count = input.read(block)) != -1;) {
            if (count > INPUT_LIMIT - bytes.size()) throw new IOException("The file exceeds 16 MB.");
            bytes.write(block, 0, count);
        }
        byte[] data = bytes.toByteArray();
        if (data.length >= 2) {
            int marker = (data[0] & 0xff) << 8 | (data[1] & 0xff);
            if (marker == 0xfffe || marker == 0xfeff) return new String(data, StandardCharsets.UTF_16);
        }
        String text = new String(data, StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    private static final class Markdown {
        private static final List<Extension> EXTENSIONS = Arrays.asList(TablesExtension.create(),
                StrikethroughExtension.create(), TaskListItemsExtension.create());
        private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
        private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
                .escapeHtml(true).sanitizeUrls(true).build();

        static String render(String text) {
            return RENDERER.render(PARSER.parse(text));
        }
    }

    private DocumentText() {}
}
