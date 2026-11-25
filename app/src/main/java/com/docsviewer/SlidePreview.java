package com.docsviewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SlidePreview {
    private final OfficePackage office;
    private final Preview preview = new Preview("Slide");
    private final Map<String, PartNode> sharedParts = new HashMap<>();
    private final double width;
    private final double height;

    static Preview pptx(OfficePackage office) throws IOException {
        String path = OfficePackage.related(office.links(""), "officeDocument", "ppt/presentation.xml");
        PartNode presentation = office.xml(path);
        SlidePreview reader = new SlidePreview(office, presentation.child("sldSz"));
        Map<String, OfficePackage.Link> links = office.links(path);
        List<String> slides = new ArrayList<>();
        for (PartNode slide : presentation.child("sldIdLst").children("sldId")) {
            String target = OfficePackage.target(links, slide.attr("rel:id"));
            if (!target.isEmpty()) slides.add(target);
        }
        if (slides.isEmpty()) slides = office.numbered("ppt/slides/slide");
        for (String slide : slides) reader.add(slide, reader.preview.sections.size() + 1);
        if (reader.preview.sections.isEmpty()) throw new IOException("No presentation slides could be read.");
        return reader.preview;
    }

    private SlidePreview(OfficePackage office, PartNode dimensions) {
        this.office = office;
        width = Math.max(1000, Markup.number(dimensions.attr("cx"), 12_192_000));
        height = Math.max(1000, Markup.number(dimensions.attr("cy"), 6_858_000));
    }

    private PartNode shared(String path) throws IOException {
        PartNode prior = sharedParts.get(path);
        if (prior != null) return prior;
        PartNode node = office.xml(path);
        sharedParts.put(path, node);
        return node;
    }

    private void add(String path, int number) throws IOException {
        PartNode slide = office.required(path).child("cSld");
        Map<String, OfficePackage.Link> links = office.links(path);
        String layoutPath = OfficePackage.related(links, "slideLayout", "");
        String masterPath = OfficePackage.related(office.links(layoutPath), "slideMaster", "");
        String themePath = OfficePackage.related(office.links(masterPath), "theme", "ppt/theme/theme1.xml");
        PartNode layout = shared(layoutPath).child("cSld");
        PartNode master = shared(masterPath).child("cSld");
        Theme theme = new Theme(shared(themePath).first("clrScheme"));
        SlideCanvas canvas = new SlideCanvas(width, height);
        String background = theme.fill(slide.child("bg").child("bgPr"));
        if (background.isEmpty()) background = theme.fill(layout.child("bg").child("bgPr"));
        if (background.isEmpty()) background = theme.fill(master.child("bg").child("bgPr"));
        if (background.isEmpty()) background = theme.color(slide.child("bg").child("bgRef"));
        String label = "Slide " + number;
        for (PartNode shape : slide.child("spTree").children()) {
            if (isTitle(shape.first("ph").attr("type"))) {
                for (PartNode text : shape.descendants("t")) {
                    String title = text.text().trim();
                    if (!title.isEmpty()) {
                        label += " · " + (title.length() > 120 ? title.substring(0, 120) : title);
                        break;
                    }
                }
                break;
            }
        }
        drawTree(master.child("spTree"), PartNode.EMPTY, PartNode.EMPTY,
                office.links(masterPath), theme, canvas, true, null, null);
        drawTree(layout.child("spTree"), PartNode.EMPTY, PartNode.EMPTY,
                office.links(layoutPath), theme, canvas, true, null, null);
        drawTree(slide.child("spTree"), layout.child("spTree"), master.child("spTree"), links, theme,
                canvas, false, null, null);
        preview.add(label, canvas.html(background, label));
    }

    private void drawTree(PartNode tree, PartNode layout, PartNode master, Map<String, OfficePackage.Link> links,
            Theme theme, SlideCanvas canvas, boolean decoration, SlideCanvas.Box outer, SlideCanvas.Box inner)
            throws IOException {
        int fallback = 0;
        for (PartNode shape : tree.children()) {
            if (!shape.name.matches("sp|pic|graphicFrame|cxnSp|grpSp")) continue;
            PartNode placeholder = shape.first("ph");
            if (decoration && placeholder != PartNode.EMPTY) continue;
            String type = placeholder.attr("type");
            PartNode layoutShape = placeholder(layout, placeholder);
            PartNode masterShape = placeholder(master, placeholder);
            PartNode properties = shape.child("spPr");
            PartNode transform = properties.child("xfrm");
            if (transform == PartNode.EMPTY) transform = shape.child("xfrm");
            if (transform == PartNode.EMPTY) transform = shape.child("grpSpPr").child("xfrm");
            if (transform == PartNode.EMPTY) transform = layoutShape.child("spPr").child("xfrm");
            if (transform == PartNode.EMPTY) transform = masterShape.child("spPr").child("xfrm");
            SlideCanvas.Box geometry = box(transform, "off", "ext");
            if (geometry == null && placeholder != PartNode.EMPTY) {
                geometry = isTitle(type) ? new SlideCanvas.Box(width * .07, height * .06, width * .86, height * .17, 0)
                        : new SlideCanvas.Box(width * .1, height * (.25 + .13 * Math.min(fallback++, 4)),
                                width * .8, height * .52, 0);
            }
            if (geometry == null) continue;
            if (outer != null && inner != null) geometry = geometry.within(outer, inner);
            if (shape.name.equals("grpSp")) {
                drawTree(shape, PartNode.EMPTY, PartNode.EMPTY, links, theme, canvas, false,
                        geometry, box(transform, "chOff", "chExt"));
            } else if (shape.name.equals("pic")) {
                String id = shape.first("blip").attr("rel:embed");
                canvas.image(geometry, office.imageUrl(preview, OfficePackage.target(links, id)));
            } else if (shape.name.equals("cxnSp")) {
                canvas.line(geometry, theme.fill(properties.child("ln")));
            } else if (shape.name.equals("graphicFrame")) {
                PartNode table = shape.first("tbl");
                if (table != PartNode.EMPTY) canvas.box(geometry, "padding:0;", table(table, theme, canvas.scale));
            } else {
                String css = shapeCss(properties, theme);
                PartNode body = shape.child("txBody");
                String anchor = body.child("bodyPr").attr("anchor");
                if (anchor.equals("ctr")) css += "justify-content:center;";
                if (anchor.equals("b")) css += "justify-content:flex-end;";
                PartNode bodyStyle = layoutShape.child("txBody").child("lstStyle");
                if (bodyStyle == PartNode.EMPTY) bodyStyle = masterShape.child("txBody").child("lstStyle");
                double fontSize = isTitle(type) ? 36 : type.equals("subTitle") ? 26 : 24;
                if (body == PartNode.EMPTY && css.isEmpty()) continue;
                canvas.box(geometry, css + "font-size:" + fontSize + "px;",
                        text(body, bodyStyle, theme, canvas.scale));
            }
        }
    }

    private static PartNode placeholder(PartNode tree, PartNode wanted) {
        if (wanted == PartNode.EMPTY) return PartNode.EMPTY;
        String index = wanted.attr("idx");
        String type = wanted.attr("type");
        for (PartNode shape : tree.children("sp")) {
            PartNode ph = shape.first("ph");
            if (ph == PartNode.EMPTY) continue;
            if (!index.isEmpty() ? index.equals(ph.attr("idx"))
                    : type.equals(ph.attr("type")) || isTitle(type) && isTitle(ph.attr("type"))) return shape;
        }
        return PartNode.EMPTY;
    }

    private static SlideCanvas.Box box(PartNode transform, String position, String size) {
        PartNode offset = transform.child(position);
        PartNode extent = transform.child(size);
        if (offset == PartNode.EMPTY || extent == PartNode.EMPTY) return null;
        return new SlideCanvas.Box(Markup.number(offset.attr("x"), 0), Markup.number(offset.attr("y"), 0),
                Markup.number(extent.attr("cx"), 0), Markup.number(extent.attr("cy"), 0),
                Markup.number(transform.attr("rot"), 0) / 60_000);
    }

    private static String shapeCss(PartNode properties, Theme theme) {
        String css = Markup.cssColor("background-color", theme.fill(properties));
        String line = theme.fill(properties.child("ln"));
        if (!line.isEmpty()) css += "border:1px solid " + line + ";";
        switch (properties.child("prstGeom").attr("prst")) {
            case "ellipse": css += "border-radius:50%;"; break;
            case "roundRect": css += "border-radius:12px;"; break;
            case "triangle": css += "clip-path:polygon(50% 0,100% 100%,0 100%);"; break;
            case "diamond": css += "clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%);"; break;
            default: break;
        }
        return css;
    }

    private static String text(PartNode body, PartNode inherited, Theme theme, double scale) {
        StringBuilder html = new StringBuilder();
        for (PartNode paragraph : body.children("p")) {
            PartNode properties = paragraph.child("pPr");
            int level = Math.max(0, Math.min(8, Markup.integer(properties.attr("lvl"), 0)));
            String levelName = "lvl" + (level + 1) + "pPr";
            PartNode local = body.child("lstStyle").child(levelName);
            PartNode common = inherited.child(levelName);
            String css = Markup.align(common.attr("algn")) + Markup.align(local.attr("algn"))
                    + Markup.align(alignment(properties.attr("algn")));
            css += runStyle(common.child("defRPr"), theme, scale) + runStyle(local.child("defRPr"), theme, scale)
                    + runStyle(properties.child("defRPr"), theme, scale);
            PartNode bullet = properties.child("buChar");
            if (bullet == PartNode.EMPTY) bullet = local.child("buChar");
            if (bullet == PartNode.EMPTY) bullet = common.child("buChar");
            if (properties.child("buNone") != PartNode.EMPTY) bullet = PartNode.EMPTY;
            StringBuilder sentence = new StringBuilder();
            if (bullet != PartNode.EMPTY) {
                css += Markup.size("padding-inline-start", 9 + level * 14);
                sentence.append(Markup.escape(bullet.attr("char"))).append("&nbsp; ");
            }
            for (PartNode run : paragraph.children()) {
                if (run.name.equals("r") || run.name.equals("fld")) {
                    sentence.append(Markup.wrap("span", runStyle(run.child("rPr"), theme, scale),
                            Markup.escape(run.child("t").text())));
                } else if (run.name.equals("br")) {
                    sentence.append("<br>");
                }
            }
            html.append(Markup.wrap("p", css, sentence.length() == 0 ? "&nbsp;" : sentence.toString()));
        }
        return html.toString();
    }

    private static String alignment(String value) {
        switch (value) {
            case "ctr": return "center";
            case "r": return "right";
            case "l": return "left";
            case "just": return "justify";
            default: return value;
        }
    }

    private static String runStyle(PartNode run, Theme theme, double scale) {
        String css = Markup.cssColor("color", theme.fill(run));
        if (run.attr("b").equals("1") || run.attr("b").equals("true")) css += "font-weight:bold;";
        if (run.attr("i").equals("1") || run.attr("i").equals("true")) css += "font-style:italic;";
        if (!run.attr("u").isEmpty() && !run.attr("u").equals("none")) css += "text-decoration:underline;";
        css += Markup.size("font-size", Markup.number(run.attr("sz"), Double.NaN) * 127 * scale);
        return css + Markup.font(run.child("latin").attr("typeface"));
    }

    private static String table(PartNode table, Theme theme, double scale) {
        StringBuilder html = new StringBuilder("<table class=\"doc-table slide-table\"><tbody>");
        for (PartNode row : table.children("tr")) {
            html.append("<tr>");
            for (PartNode cell : row.children("tc")) {
                if (cell.attr("hMerge").equals("1") || cell.attr("vMerge").equals("1")) continue;
                html.append("<td colspan=\"").append(Math.max(1, Markup.integer(cell.attr("gridSpan"), 1)))
                        .append("\" rowspan=\"").append(Math.max(1, Markup.integer(cell.attr("rowSpan"), 1)))
                        .append('"').append(Markup.style(Markup.cssColor("background-color", theme.fill(cell.child("tcPr")))))
                        .append('>').append(text(cell.child("txBody"), PartNode.EMPTY, theme, scale)).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    private static boolean isTitle(String type) {
        return type.equals("title") || type.equals("ctrTitle");
    }

    private static final class Theme {
        private final Map<String, String> colors = new HashMap<>();

        Theme(PartNode scheme) {
            for (PartNode node : scheme.children()) {
                String color = color(node);
                if (!color.isEmpty()) colors.put(node.name, color);
            }
        }

        String fill(PartNode node) {
            return color(node.child("solidFill"));
        }

        String color(PartNode node) {
            String value = node.child("srgbClr").attr("val");
            if (!value.isEmpty()) return Markup.color(value);
            value = node.child("sysClr").attr("lastClr");
            if (!value.isEmpty()) return Markup.color(value);
            switch (node.child("schemeClr").attr("val")) {
                case "bg1": return colors.getOrDefault("lt1", "#ffffff");
                case "bg2": return colors.getOrDefault("lt2", "#ffffff");
                case "tx1": return colors.getOrDefault("dk1", "#000000");
                case "tx2": return colors.getOrDefault("dk2", "#000000");
                default: return colors.getOrDefault(node.child("schemeClr").attr("val"), "");
            }
        }
    }
}
