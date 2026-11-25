package com.docsviewer;

import java.util.Locale;
import java.util.regex.Pattern;

final class Markup {
    private static final Pattern HEX = Pattern.compile("(?i)#?(?:[0-9a-f]{3}|[0-9a-f]{6})");
    private static final Pattern FONT_NAME = Pattern.compile("[\\p{L}\\p{N} ._\\-]{1,60}");

    static String escape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': result.append("&amp;"); break;
                case '<': result.append("&lt;"); break;
                case '>': result.append("&gt;"); break;
                case '"': result.append("&quot;"); break;
                case '\'': result.append("&#39;"); break;
                default: result.append(c);
            }
        }
        return result.toString();
    }

    static String wrap(String tag, String css, String html) {
        return "<" + tag + " class=\"formatted\"" + style(css) + ">" + html + "</" + tag + ">";
    }

    static String style(String css) {
        return css.isEmpty() ? "" : " style=\"" + escape(css) + "\"";
    }

    static String color(String text) {
        if (!HEX.matcher(text).matches()) return "";
        return text.startsWith("#") ? text : "#" + text;
    }

    static String font(String name) {
        return FONT_NAME.matcher(name).matches() ? "font-family:'" + name + "',sans-serif;" : "";
    }

    static String align(String value) {
        switch (value) {
            case "center": case "right": case "left": case "justify": case "start": case "end":
                return "text-align:" + value + ";";
            case "both": return "text-align:justify;";
            default: return "";
        }
    }

    static String cssColor(String property, String value) {
        String color = color(value);
        return color.isEmpty() ? "" : property + ":" + color + ";";
    }

    static int integer(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static double number(String text, double fallback) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static double length(String text, double fallback) {
        if (text.length() < 3) return fallback;
        double value = number(text.substring(0, text.length() - 2), Double.NaN);
        if (!Double.isFinite(value)) return fallback;
        switch (text.substring(text.length() - 2)) {
            case "pt": return value * 96 / 72;
            case "in": return value * 96;
            case "cm": return value * 96 / 2.54;
            case "mm": return value * 96 / 25.4;
            case "pc": return value * 16;
            case "px": return value;
            default: return fallback;
        }
    }

    static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String size(String property, double value) {
        return Double.isFinite(value) && Math.abs(value) <= 4000
                ? property + ":" + decimal(value) + "px;" : "";
    }

    private Markup() {}
}
