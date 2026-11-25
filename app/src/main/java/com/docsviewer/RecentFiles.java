package com.docsviewer;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.UriPermission;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class RecentFiles {
    private static final String KEY = "files";
    private static final int MAX_FILES = 8;
    private final SharedPreferences preferences;
    private final ContentResolver resolver;

    RecentFiles(SharedPreferences preferences, ContentResolver resolver) {
        this.preferences = preferences;
        this.resolver = resolver;
    }

    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new Entry(Uri.parse(item.getString("uri")), item.getString("name"),
                        item.getString("kind"), item.getInt("position")));
            }
        } catch (JSONException ignored) {
            // An unreadable preference should not prevent the reader from launching.
        }
        return result;
    }

    int position(Uri uri) {
        for (Entry entry : entries()) {
            if (uri.equals(entry.uri)) return entry.position;
        }
        return 0;
    }

    void remember(Uri uri, String name, String kind, int position) {
        List<Entry> entries = entries();
        if (!entries.removeIf(entry -> uri.equals(entry.uri)) && !hasPermission(uri)) return;
        entries.add(0, new Entry(uri, name, kind, position));
        if (entries.size() > MAX_FILES) release(entries.remove(entries.size() - 1).uri);
        save(entries);
    }

    void forget(Uri uri) {
        List<Entry> entries = entries();
        if (!entries.removeIf(entry -> uri.equals(entry.uri))) return;
        release(uri);
        save(entries);
    }

    boolean keepPermission(Uri uri) {
        if (hasPermission(uri)) return false;
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    void release(Uri uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The provider may already have removed the permission.
        }
    }

    private boolean hasPermission(Uri uri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()) return true;
        }
        return false;
    }

    private void save(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("uri", entry.uri.toString());
                item.put("name", entry.name);
                item.put("kind", entry.kind);
                item.put("position", entry.position);
                array.put(item);
            }
            preferences.edit().putString(KEY, array.toString()).apply();
        } catch (JSONException ignored) {
            // All fields are scalar values read from a local document.
        }
    }

    static final class Entry {
        final Uri uri;
        final String name;
        final String kind;
        final int position;

        Entry(Uri uri, String name, String kind, int position) {
            this.uri = uri;
            this.name = name;
            this.kind = kind;
            this.position = position;
        }
    }
}
