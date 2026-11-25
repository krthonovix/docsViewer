package com.docsviewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Preview {
    static final String ORIGIN = "https://docs-viewer.invalid/";
    private static final int MAX_MARKUP = 12_000_000;
    final String unit;
    final List<Section> sections = new ArrayList<>();
    final Map<String, Asset> assets = new HashMap<>();
    private final Map<String, String> assetUrls = new HashMap<>();
    private int markup;

    Preview(String unit) {
        this.unit = unit;
    }

    void add(String title, String html) throws IOException {
        if (html.length() > MAX_MARKUP - markup) {
            throw new IOException("The formatted preview is too large.");
        }
        markup += html.length();
        sections.add(new Section(title, html));
    }

    String image(String key, byte[] bytes, String mime) {
        if (bytes == null || mime == null) return "";
        String url = assetUrls.get(key);
        if (url == null) {
            url = ORIGIN + "media/" + assets.size();
            assetUrls.put(key, url);
            assets.put(url, new Asset(bytes, mime));
        }
        return url;
    }

    static final class Section {
        final String title;
        final String html;

        Section(String title, String html) {
            this.title = title;
            this.html = html;
        }
    }

    static final class Asset {
        final byte[] bytes;
        final String mime;

        Asset(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }
}
