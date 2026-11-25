package com.docsviewer;

final class SlideCanvas {
    private static final double VIEW_WIDTH = 960;
    final double scale;
    private final double height;
    private final StringBuilder shapes = new StringBuilder();

    SlideCanvas(double width, double height) {
        scale = VIEW_WIDTH / width;
        this.height = height * scale;
    }

    void box(Box box, String css, String content) {
        if (box.width <= 0 || box.height <= 0 || content.isEmpty() && css.isEmpty()) return;
        shapes.append("<foreignObject").append(coordinates(box)).append(transform(box)).append('>')
                .append("<div xmlns=\"http://www.w3.org/1999/xhtml\" class=\"shape\"")
                .append(Markup.style(css)).append('>').append(content).append("</div></foreignObject>");
    }

    void image(Box box, String url) {
        if (url.isEmpty() || box.width <= 0 || box.height <= 0) return;
        shapes.append("<image").append(coordinates(box)).append(transform(box))
                .append(" href=\"").append(url).append("\" preserveAspectRatio=\"none\"/>");
    }

    void line(Box box, String color) {
        shapes.append("<line x1=\"").append(px(box.x)).append("\" y1=\"").append(px(box.y))
                .append("\" x2=\"").append(px(box.x + box.width)).append("\" y2=\"")
                .append(px(box.y + box.height)).append("\" stroke=\"")
                .append(color.isEmpty() ? "#49574f" : color).append("\" stroke-width=\"2\"")
                .append(transform(box)).append("/>");
    }

    String html(String background, String label) {
        return "<figure class=\"slide\" aria-label=\"" + Markup.escape(label) + "\"><svg viewBox=\"0 0 960 "
                + Markup.decimal(height) + "\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"" + (background.isEmpty() ? "#fff" : background)
                + "\"/>" + shapes + "</svg></figure>";
    }

    private String coordinates(Box box) {
        return " x=\"" + px(box.x) + "\" y=\"" + px(box.y) + "\" width=\"" + px(box.width)
                + "\" height=\"" + px(box.height) + "\"";
    }

    private String transform(Box box) {
        if (box.rotation == 0) return "";
        return " transform=\"rotate(" + Markup.decimal(box.rotation) + " " + px(box.x + box.width / 2)
                + " " + px(box.y + box.height / 2) + ")\"";
    }

    private String px(double value) {
        return Markup.decimal(value * scale);
    }

    static final class Box {
        final double x;
        final double y;
        final double width;
        final double height;
        final double rotation;

        Box(double x, double y, double width, double height, double rotation) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }

        Box within(Box outer, Box inner) {
            if (inner.width <= 0 || inner.height <= 0) return this;
            double sx = outer.width / inner.width;
            double sy = outer.height / inner.height;
            return new Box(outer.x + (x - inner.x) * sx, outer.y + (y - inner.y) * sy,
                    width * sx, height * sy, rotation + outer.rotation);
        }
    }
}
