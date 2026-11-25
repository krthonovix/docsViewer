package com.docsviewer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SmokeDocuments {
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String LOGO_PNG = "iVBORw0KGgoAAAANSUhEUgAAAKAAAABACAIAAAAS6ev4AAAAuElEQVR4nO3RAQnAMBDAwHcxWVNRV/NTH1MyFaMQDk5AIHOtm7A5XsCvDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOM7gOIPjDI4zOG6edxNmcJzBcQbHGRxncJzBcQbHGRxncJzBcQbHGRxncJzBcQbHGRxncJzBcQbHGRxncJzBcQbvtg9ME+EciworWAAAAABJRU5ErkJggg==";
    private static final String ODF = " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\"";

    static void write(String name, OutputStream output) throws IOException {
        switch (name) {
            case "sample.docx":
                archive(output, "word/document.xml",
                        "<w:document xmlns:w=\"" + WORD + "\" xmlns:r=\"" + REL
                                + "\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                                + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>"
                                + "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Word heading</w:t></w:r></w:p>"
                                + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold word</w:t></w:r></w:p>"
                                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Word cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                                + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1524000\" cy=\"609600\"/>"
                                + "<wp:docPr descr=\"Embedded logo\"/><a:graphic><a:blip r:embed=\"logo\"/></a:graphic>"
                                + "</wp:inline></w:drawing></w:r></w:p></w:body></w:document>",
                        "word/_rels/document.xml.rels",
                        "<Relationships><Relationship Id=\"logo\" Target=\"media/logo.png\"/></Relationships>",
                        "word/media/logo.png", LOGO_PNG);
                break;
            case "sample.xlsx":
                archive(output, "xl/workbook.xml",
                        "<workbook xmlns:r=\"" + REL + "\"><sheets>"
                                + "<sheet name=\"Budget\" sheetId=\"7\" r:id=\"rId2\"/>"
                                + "<sheet name=\"Totals\" sheetId=\"4\" r:id=\"rId1\"/>"
                                + "</sheets></workbook>",
                        "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\""
                                + " Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\""
                                + " Target=\"worksheets/sheet2.xml\"/></Relationships>",
                        "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>2</v>"
                                + "</c></row></sheetData></worksheet>",
                        "xl/worksheets/sheet2.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\">"
                                + "<is><t>Budget grid</t></is></c></row><row r=\"4\"><c r=\"C4\"><v>100</v></c></row>"
                                + "</sheetData><mergeCells><mergeCell ref=\"A1:C1\"/><mergeCell ref=\"C4:C6\"/>"
                                + "</mergeCells></worksheet>");
                break;
            case "sample.pptx":
                archive(output, "ppt/slides/slide1.xml",
                        "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
                                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                                + "<p:cSld><p:spTree><p:sp><p:spPr><a:xfrm><a:off x=\"952500\" y=\"760000\"/>"
                                + "<a:ext cx=\"10500000\" cy=\"1800000\"/></a:xfrm></p:spPr>"
                                + "<p:txBody><a:p><a:r><a:rPr sz=\"4200\" b=\"1\"/><a:t>PowerPoint slide</a:t></a:r></a:p>"
                                + "</p:txBody></p:sp><p:sp><p:spPr><a:xfrm><a:off x=\"1000000\" y=\"3500000\"/>"
                                + "<a:ext cx=\"2000000\" cy=\"900000\"/></a:xfrm><a:prstGeom prst=\"ellipse\"/></p:spPr>"
                                + "<p:style><a:fillRef idx=\"1\"><a:srgbClr val=\"17654F\"/></a:fillRef></p:style>"
                                + "</p:sp></p:spTree></p:cSld></p:sld>");
                break;
            case "sample.odt":
                archive(output, "content.xml", odf("<office:text><text:h text:outline-level=\"2\">ODT heading</text:h>"
                        + "<text:list><text:list-item><text:p>ODT item</text:p></text:list-item></text:list></office:text>"));
                break;
            case "sample.ods":
                archive(output, "content.xml", odf("<office:spreadsheet><table:table table:name=\"Inventory\">"
                        + "<table:table-row><table:table-cell table:number-columns-repeated=\"2\"/>"
                        + "<table:table-cell><text:p>ODS cell</text:p></table:table-cell></table:table-row>"
                        + "</table:table></office:spreadsheet>"));
                break;
            case "sample.odp":
                archive(output, "content.xml", odf("<office:presentation><draw:page draw:name=\"Intro\">"
                        + "<draw:frame svg:x=\"2cm\" svg:y=\"2cm\" svg:width=\"18cm\" svg:height=\"5cm\">"
                        + "<draw:text-box><text:p>ODP slide</text:p></draw:text-box></draw:frame>"
                        + "</draw:page></office:presentation>"));
                break;
            case "sample.md": text(output, "# Markdown heading\n\n**Bold** ~~deleted~~\n\n- [x] Done\n\n| Item | Count |\n| --- | --- |\n| A | 3 |\n"); break;
            case "sample.markdown": text(output, "> Markdown quote"); break;
            case "sample.csv": text(output, "Name,Note,Price\r\nItem,\"Comma, kept\nnext line\",10"); break;
            case "sample.tsv": text(output, "Name\tPrice\nItem\t20"); break;
            case "sample.html": case "sample.htm":
                text(output, "<article><h1>HTML heading</h1><table><tr><td>HTML cell</td></tr></table>"
                        + "<script>won't load</script><img src=\"https://example.invalid/x\" onerror=\"x()\"></article>");
                break;
            case "sample.json": text(output, "{\"title\":\"JSON sample\",\"count\":5}"); break;
            case "sample.xml": text(output, "<root><entry id=\"7\"/></root>"); break;
            case "sample.java": text(output, "public class Example {}"); break;
            case "sample.kt": text(output, "fun main() { val answer = 42 }"); break;
            case "sample.js": text(output, "const message = 'JS sample';"); break;
            case "sample.css": text(output, "p { color: #17654f; }"); break;
            case "sample.log": text(output, "INFO First line\nINFO Second line"); break;
            default: text(output, "Text reader sample\n    indented text"); break;
        }
    }

    private static String odf(String body) {
        return "<office:document-content" + ODF + "><office:body>" + body + "</office:body></office:document-content>";
    }

    private static void archive(OutputStream output, String... entries) throws IOException {
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length; i += 2) {
                archive.putNextEntry(new ZipEntry(entries[i]));
                if (entries[i].endsWith(".png")) archive.write(Base64.getDecoder().decode(entries[i + 1]));
                else text(archive, entries[i + 1]);
                archive.closeEntry();
            }
        }
    }

    private static void text(OutputStream output, String content) throws IOException {
        output.write(content.getBytes(StandardCharsets.UTF_8));
    }

    private SmokeDocuments() {}
}
