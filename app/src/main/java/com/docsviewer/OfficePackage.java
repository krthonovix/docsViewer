package com.docsviewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfficePackage {
    private static final int MB = 1024 * 1024;
    private final Map<String, byte[]> parts = new HashMap<>();

    static OfficePackage read(InputStream input) throws IOException {
        OfficePackage office = new OfficePackage();
        int inflated = 0;
        int retained = 0;
        int count = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++count > 4000) throw new IOException("The document includes too many files.");
                String name = entry.getName();
                boolean wanted = !entry.isDirectory() && relevant(name);
                ByteArrayOutputStream part = wanted ? new ByteArrayOutputStream() : null;
                for (int read; (read = zip.read(buffer)) != -1;) {
                    inflated += read;
                    if (inflated > 96 * MB) throw new IOException("The document expands to more than 96 MB.");
                    if (part == null) continue;
                    if (part.size() + read > 10 * MB || retained + read > 32 * MB) {
                        throw new IOException("The document is too large for the formatted preview.");
                    }
                    retained += read;
                    part.write(buffer, 0, read);
                }
                if (part != null) office.parts.put(name, part.toByteArray());
            }
        }
        return office;
    }

    private static boolean relevant(String name) {
        if (name.equals("content.xml") || name.equals("styles.xml") || name.equals("_rels/.rels")) return true;
        if (name.startsWith("Pictures/")) return imageType(name) != null;
        if (name.startsWith("word/") || name.startsWith("xl/") || name.startsWith("ppt/")) {
            return name.endsWith(".xml") || name.endsWith(".rels") || imageType(name) != null;
        }
        return false;
    }

    PartNode xml(String path) throws IOException {
        return PartNode.parse(parts.get(path));
    }

    PartNode required(String path) throws IOException {
        PartNode root = xml(path);
        if (root == PartNode.EMPTY) throw new IOException("The document is missing its main content.");
        return root;
    }

    Map<String, Link> links(String source) throws IOException {
        int slash = source.lastIndexOf('/');
        String folder = slash < 0 ? "" : source.substring(0, slash + 1);
        String name = source.substring(slash + 1);
        PartNode root = xml(folder + "_rels/" + name + ".rels");
        Map<String, Link> result = new HashMap<>();
        for (PartNode node : root.children("Relationship")) {
            if ("External".equalsIgnoreCase(node.attr("TargetMode"))) continue;
            String target = resolve(source, node.attr("Target"));
            if (!target.isEmpty()) result.put(node.attr("Id"), new Link(target, node.attr("Type")));
        }
        return result;
    }

    static String related(Map<String, Link> links, String type, String fallback) {
        for (Link link : links.values()) {
            if (link.type.endsWith("/" + type)) return link.path;
        }
        return fallback;
    }

    static String target(Map<String, Link> links, String id) {
        Link link = links.get(id);
        return link == null ? "" : link.path;
    }

    static String resolve(String source, String reference) {
        if (reference.isEmpty() || reference.indexOf('\\') >= 0) return "";
        try {
            URI relative = new URI(reference.replace(" ", "%20"));
            if (relative.isAbsolute() || relative.getRawAuthority() != null) return "";
            URI base = new URI(null, null, "/" + source, null);
            String path = base.resolve(relative).normalize().getPath();
            if (path == null || !path.startsWith("/") || path.contains("/../") || path.endsWith("/..")) return "";
            return path.substring(1);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return "";
        }
    }

    List<String> numbered(String prefix) {
        List<String> names = new ArrayList<>();
        for (String path : parts.keySet()) {
            if (path.startsWith(prefix) && path.endsWith(".xml")
                    && path.substring(prefix.length(), path.length() - 4).matches("[0-9]+")) names.add(path);
        }
        Collections.sort(names, (a, b) -> a.length() == b.length()
                ? a.compareTo(b) : Integer.compare(a.length(), b.length()));
        return names;
    }

    String image(Preview preview, String path, String alt) {
        String url = imageUrl(preview, path);
        return url.isEmpty() ? "" : "<img src=\"" + url + "\" alt=\"" + Markup.escape(alt) + "\">";
    }

    String imageUrl(Preview preview, String path) {
        return preview.image(path, parts.get(path), imageType(path));
    }

    private static String imageType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return null;
    }

    static final class Link {
        final String path;
        final String type;

        Link(String path, String type) {
            this.path = path;
            this.type = type;
        }
    }
}
