package com.docsviewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PartNode {
    static final PartNode EMPTY = new PartNode("", "", "", Collections.emptyMap());
    final String name;
    final String namespace;
    final String value;
    final Map<String, String> attributes;
    private List<PartNode> content;

    private PartNode(String name, String namespace, String value, Map<String, String> attributes) {
        this.name = name;
        this.namespace = namespace;
        this.value = value;
        this.attributes = attributes;
    }

    static PartNode parse(byte[] bytes) throws IOException {
        if (bytes == null) return EMPTY;
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            List<PartNode> stack = new ArrayList<>();
            PartNode root = EMPTY;
            int nodes = 0;
            for (int event; (event = parser.nextToken()) != XmlPullParser.END_DOCUMENT;) {
                if (event == XmlPullParser.DOCDECL) {
                    throw new IOException("Document XML cannot contain a DOCTYPE.");
                }
                if (event == XmlPullParser.START_TAG) {
                    if (stack.size() >= 80 || ++nodes > 220_000) {
                        throw new IOException("The document structure is too complex to preview.");
                    }
                    Map<String, String> attributes = parser.getAttributeCount() == 0
                            ? Collections.emptyMap() : new HashMap<>();
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String name = parser.getAttributeName(i);
                        String value = parser.getAttributeValue(i);
                        String namespace = parser.getAttributeNamespace(i);
                        if (namespace == null || namespace.isEmpty()) attributes.put(name, value);
                        else attributes.putIfAbsent(name, value);
                        if (namespace != null && namespace.endsWith("/relationships")) {
                            attributes.put("rel:" + name, value);
                        }
                    }
                    PartNode node = new PartNode(parser.getName(), parser.getNamespace(), "", attributes);
                    if (stack.isEmpty()) root = node;
                    else stack.get(stack.size() - 1).add(node);
                    stack.add(node);
                } else if (event == XmlPullParser.END_TAG) {
                    stack.remove(stack.size() - 1);
                } else if (!stack.isEmpty() && (event == XmlPullParser.TEXT
                        || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF
                        || event == XmlPullParser.IGNORABLE_WHITESPACE)) {
                    String text = parser.getText();
                    if (text != null && !text.isEmpty()) {
                        if (++nodes > 220_000) throw new IOException("The document contains too much text.");
                        stack.get(stack.size() - 1).add(new PartNode("#text", "", text, Collections.emptyMap()));
                    }
                }
            }
            return root;
        } catch (XmlPullParserException e) {
            throw new IOException("A document XML part could not be read.", e);
        }
    }

    private void add(PartNode node) {
        if (content == null) content = new ArrayList<>();
        content.add(node);
    }

    List<PartNode> children() {
        return content == null ? Collections.emptyList() : content;
    }

    List<PartNode> children(String name) {
        List<PartNode> result = new ArrayList<>();
        for (PartNode node : children()) if (node.name.equals(name)) result.add(node);
        return result;
    }

    PartNode child(String name) {
        for (PartNode node : children()) if (node.name.equals(name)) return node;
        return EMPTY;
    }

    PartNode first(String name) {
        if (this.name.equals(name)) return this;
        for (PartNode node : children()) {
            PartNode match = node.first(name);
            if (match != EMPTY) return match;
        }
        return EMPTY;
    }

    List<PartNode> descendants(String name) {
        List<PartNode> result = new ArrayList<>();
        collect(name, result);
        return result;
    }

    private void collect(String name, List<PartNode> output) {
        if (this.name.equals(name)) output.add(this);
        for (PartNode node : children()) node.collect(name, output);
    }

    String attr(String key) {
        String result = attributes.get(key);
        return result == null ? "" : result;
    }

    String text() {
        if (content == null) return value;
        StringBuilder result = new StringBuilder();
        appendText(result);
        return result.toString();
    }

    private void appendText(StringBuilder output) {
        if (content == null) output.append(value);
        else for (PartNode child : content) child.appendText(output);
    }
}
