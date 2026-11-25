package com.docsviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_DOCUMENT = 42;
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private LinearLayout toolbar;
    private FrameLayout body;
    private RecentFiles recent;
    private Uri documentUri;
    private Uri pendingGrant;
    private String documentName = "";
    private String kind = "";
    private int position;
    private int pageCount;
    private boolean busy;
    private boolean zoomed;
    private Preview preview;
    private PreviewView previewView;
    private Bitmap page;
    private FrameLayout pdfViewport;

    // The PDF session is confined to the worker. The request ID invalidates stale results.
    private PdfDocument pdf;
    private volatile int requestId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        recent = new RecentFiles(getSharedPreferences("recent", MODE_PRIVATE), getContentResolver());
        root = column();
        root.setBackgroundColor(color(R.color.background));
        toolbar = row();
        toolbar.setBackgroundColor(color(R.color.surface));
        root.addView(toolbar, new LinearLayout.LayoutParams(MATCH, WRAP));
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setContentView(root);
        applySystemInsets();

        if (state == null) {
            showHome();
            openIntent(getIntent());
        } else {
            String uri = state.getString("uri");
            String pending = state.getString("pendingGrant");
            if (pending != null) pendingGrant = Uri.parse(pending);
            if (uri == null) showHome();
            else open(Uri.parse(uri), state.getInt("position"));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (documentUri != null) {
            state.putString("uri", documentUri.toString());
            state.putInt("position", position);
        }
        if (pendingGrant != null) state.putString("pendingGrant", pendingGrant.toString());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openIntent(intent);
    }

    private void openIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            open(uri, recent.position(uri));
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                }
            }
            root.setPadding(left, 0, right, bottom);
            toolbar.setPadding(dp(14), top + dp(7), dp(14), dp(7));
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showHome() {
        toolbar.removeAllViews();
        toolbar.addView(mark(), new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brand = label("Docs Viewer", 20, R.color.ink, true);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        brandParams.setMarginStart(dp(12));
        toolbar.addView(brand, brandParams);

        LinearLayout content = column();
        int side = readingMargin(620, 20);
        content.setPadding(side, dp(24), side, dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        setBody(scroll);

        LinearLayout hero = column();
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff142f28, 0xff1b5948});
        backdrop.setCornerRadius(dp(24));
        hero.setBackground(backdrop);
        hero.setPadding(dp(22), dp(23), dp(22), dp(22));
        content.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView headline = label("Open a document", 29, R.color.ink, true);
        headline.setTextColor(Color.WHITE);
        heading(headline);
        hero.addView(headline);
        TextView formats = label("PDF, Word, Excel, PowerPoint, OpenDocument and text files.", 15,
                R.color.muted, false);
        formats.setTextColor(0xffd3e9df);
        addWithTopMargin(hero, formats, 8);
        Button browse = button("Browse files", true, v -> pick());
        browse.setTextColor(0xff183d31);
        browse.setBackground(ripple(0xffbcebd6, 15, false));
        addWithTopMargin(hero, browse, 20);

        TextView recentTitle = label("Recent files", 19, R.color.ink, true);
        heading(recentTitle);
        addWithTopMargin(content, recentTitle, 25);
        List<RecentFiles.Entry> entries = recent.entries();
        if (entries.isEmpty()) {
            TextView empty = label("Files you open will appear here.", 15, R.color.muted, false);
            empty.setBackground(shape(color(R.color.surface), 16, true));
            empty.setPadding(dp(16), dp(15), dp(16), dp(15));
            addWithTopMargin(content, empty, 9);
        } else {
            for (RecentFiles.Entry entry : entries) addWithTopMargin(content, recentRow(entry), 8);
        }
    }

    private View recentRow(RecentFiles.Entry entry) {
        LinearLayout item = row();
        item.setPadding(dp(11), dp(10), dp(13), dp(10));
        item.setBackground(ripple(color(R.color.surface), 16, true));
        String type = typeLabel(entry.kind);
        TextView badge = label(type, 11, R.color.accent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(color(R.color.tint), 11, false));
        item.addView(badge, new LinearLayout.LayoutParams(dp(55), dp(40)));

        LinearLayout details = column();
        TextView name = label(entry.name, 16, R.color.ink, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        String detail = entry.kind.equals("pdf") ? "Page " + (entry.position + 1) : formatName(entry.kind);
        details.addView(label(detail, 13, R.color.muted, false));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        detailsParams.setMarginStart(dp(11));
        item.addView(details, detailsParams);
        TextView chevron = label("\u203a", 24, R.color.accent, false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(chevron);
        item.setContentDescription(entry.name + ", " + type + ", " + detail);
        item.setOnClickListener(v -> open(entry.uri, entry.position));
        item.setFocusable(true);
        item.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Remove from recent files?")
                    .setMessage(entry.name).setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        recent.forget(entry.uri);
                        showHome();
                    }).show();
            return true;
        });
        return item;
    }

    private void readerToolbar(String subtitle) {
        toolbar.removeAllViews();
        Button back = button("\u2190", false, v -> returnHome());
        back.setTextSize(23);
        back.setContentDescription("Back to files");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), WRAP));
        LinearLayout info = column();
        TextView title = label(documentName, 17, R.color.ink, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        heading(title);
        info.addView(title);
        TextView detail = label(subtitle, 12, R.color.muted, false);
        detail.setSingleLine();
        detail.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, 1);
        params.setMarginStart(dp(10));
        params.setMarginEnd(dp(8));
        toolbar.addView(info, params);
        toolbar.addView(button("Open", false, v -> pick()), new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_DOCUMENT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DOCUMENT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!uri.equals(pendingGrant)) {
            discardPendingGrant();
            if (recent.keepPermission(uri)) pendingGrant = uri;
        }
        open(uri, recent.position(uri));
    }


    private void open(Uri uri, int initialPosition) {
        if (!uri.equals(pendingGrant)) discardPendingGrant();
        int token = ++requestId;
        documentUri = uri;
        documentName = "Document";
        position = initialPosition;
        busy = true;
        zoomed = false;
        clearPreview();
        page = null;
        pdfViewport = null;
        readerToolbar("Opening file");
        showLoading();

        int width = renderWidth();
        worker.execute(() -> {
            closePdf();
            if (token != requestId) return;
            String name = fileName(uri);
            try {
                String mime = getContentResolver().getType(uri);
                String format = DocumentText.detectFormat(name, mime);
                if (format.equals("pdf")) {
                    pdf = PdfDocument.open(getContentResolver(), uri, getCacheDir(), () -> token != requestId);
                    if (token != requestId) {
                        closePdf();
                        return;
                    }
                    int count = pdf.pageCount();
                    if (count == 0) throw new IOException("This PDF has no pages.");
                    int index = Math.min(Math.max(0, initialPosition), count - 1);
                    finishPdf(token, name, index, count, pdf.render(index, width, 2304));
                } else {
                    if (format.isEmpty()) {
                        throw new IOException("This format could not be opened. Try PDF, DOCX, XLSX, "
                                + "PPTX, OpenDocument, or a text file.");
                    }
                    Preview loaded;
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("The file could not be opened.");
                        loaded = DocumentText.read(input, format);
                    }
                    runOnUiThread(() -> {
                        if (token != requestId) return;
                        documentName = name;
                        kind = format;
                        preview = loaded;
                        position = Math.min(Math.max(0, initialPosition), preview.sections.size() - 1);
                        busy = false;
                        rememberPosition();
                        showPreview();
                    });
                }
            } catch (Exception e) {
                closePdf();
                showError(token, name, e);
            }
        });
    }

    private void finishPdf(int token, String name, int index, int count, Bitmap bitmap) {
        runOnUiThread(() -> {
            if (token != requestId) {
                bitmap.recycle();
                return;
            }
            documentName = name;
            kind = "pdf";
            position = index;
            pageCount = count;
            page = bitmap;
            busy = false;
            rememberPosition();
            showPdf();
        });
    }

    private void rememberPosition() {
        recent.remember(documentUri, documentName, kind, position);
        if (documentUri.equals(pendingGrant)) pendingGrant = null;
    }

    private void showPdf() {
        readerToolbar("PDF document");
        LinearLayout screen = column();
        FrameLayout viewport = new FrameLayout(this);
        pdfViewport = viewport;
        screen.addView(viewport, new LinearLayout.LayoutParams(MATCH, 0, 1));
        screen.addView(navigation("Page", pageCount, true));
        viewport.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                viewport.post(() -> {
                    if (viewport == pdfViewport) displayPdfPage(viewport);
                });
            }
        });
        setBody(screen);
    }

    private void displayPdfPage(FrameLayout viewport) {
        int width = viewport.getWidth() - dp(24);
        int height = viewport.getHeight() - dp(24);
        if (width <= 0 || height <= 0 || page == null) return;
        viewport.removeAllViews();
        double fit = Math.min((double) width / page.getWidth(), (double) height / page.getHeight());
        double scale = zoomed ? fit * 2 : fit;
        int pageWidth = Math.max(1, (int) Math.round(page.getWidth() * scale));
        int pageHeight = Math.max(1, (int) Math.round(page.getHeight() * scale));
        ImageView image = new ImageView(this);
        image.setImageBitmap(page);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.WHITE);
        image.setContentDescription("PDF page " + (position + 1));
        if (!zoomed) {
            viewport.addView(image, new FrameLayout.LayoutParams(pageWidth, pageHeight, Gravity.CENTER));
            return;
        }
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        ScrollView vertical = new ScrollView(this);
        vertical.setFillViewport(true);
        FrameLayout canvas = new FrameLayout(this);
        canvas.setPadding(dp(12), dp(12), dp(12), dp(12));
        canvas.addView(image, new FrameLayout.LayoutParams(pageWidth, pageHeight, Gravity.CENTER));
        horizontal.addView(canvas, new FrameLayout.LayoutParams(WRAP, WRAP));
        vertical.addView(horizontal, new FrameLayout.LayoutParams(MATCH, WRAP));
        viewport.addView(vertical, new FrameLayout.LayoutParams(MATCH, MATCH));
        horizontal.post(() -> horizontal.scrollTo(Math.max(0, (pageWidth - width) / 2), 0));
    }

    private void showPreview() {
        Preview.Section section = preview.sections.get(position);
        readerToolbar(section.title.isEmpty() ? formatName(kind) : section.title);
        LinearLayout screen = column();
        int token = requestId;
        String name = documentName;
        try {
            if (previewView == null) {
                previewView = new PreviewView(this, preview.assets, () -> showError(token, name,
                        new IOException("The preview stopped. Try opening the document again.")));
            } else if (previewView.getParent() instanceof ViewGroup) {
                ((ViewGroup) previewView.getParent()).removeView(previewView);
            }
        } catch (IOException | RuntimeException error) {
            showError(token, name, new IOException("Android System WebView could not display the preview.", error));
            return;
        }
        FrameLayout viewport = new FrameLayout(this);
        viewport.addView(previewView, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout pending = new FrameLayout(this);
        pending.setBackgroundColor(color(R.color.surface));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.accent)));
        spinner.setContentDescription("Drawing preview");
        pending.addView(spinner, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        viewport.addView(pending, new FrameLayout.LayoutParams(MATCH, MATCH));
        screen.addView(viewport, new LinearLayout.LayoutParams(MATCH, 0, 1));
        if (preview.sections.size() > 1 || preview.unit.equals("Slide")) {
            screen.addView(navigation(preview.unit, preview.sections.size(), false));
        }
        setBody(screen);
        previewView.display(section, kind, () -> viewport.removeView(pending));
    }

    private void clearPreview() {
        preview = null;
        if (previewView == null) return;
        if (previewView.getParent() instanceof ViewGroup) {
            ((ViewGroup) previewView.getParent()).removeView(previewView);
        }
        previewView.destroy();
        previewView = null;
    }

    private View navigation(String unit, int count, boolean pdfMode) {
        LinearLayout footer = row();
        footer.setBackgroundColor(color(R.color.surface));
        footer.setPadding(dp(10), dp(7), dp(10), dp(7));
        String unitName = unit.toLowerCase(Locale.ROOT);

        Button previous = button("\u2039", false, v -> goTo(position - 1));
        previous.setTextSize(26);
        previous.setContentDescription("Previous " + unitName);
        setAvailable(previous, position > 0);
        footer.addView(previous, new LinearLayout.LayoutParams(dp(48), WRAP));

        Button jump = button((position + 1) + " / " + count, false, v -> showJump(unitName, count));
        jump.setContentDescription(unit + " " + (position + 1) + " of " + count
                + (count > 1 ? ". Jump to " + unitName : ""));
        setAvailable(jump, count > 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, 1);
        params.setMarginStart(dp(6));
        params.setMarginEnd(dp(6));
        footer.addView(jump, params);

        Button next = button("\u203a", false, v -> goTo(position + 1));
        next.setTextSize(26);
        next.setContentDescription("Next " + unitName);
        setAvailable(next, position + 1 < count);
        footer.addView(next, new LinearLayout.LayoutParams(dp(48), WRAP));

        if (pdfMode || unit.equals("Slide")) {
            Button zoom = button("", false, v -> {
                zoomed = !zoomed;
                updateZoomButton((Button) v);
                if (pdfMode && pdfViewport != null) {
                    displayPdfPage(pdfViewport);
                } else if (previewView != null) {
                    previewView.zoomBy(zoomed ? 2f : .5f);
                }
            });
            updateZoomButton(zoom);
            LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(dp(56), WRAP);
            zoomParams.setMarginStart(dp(6));
            footer.addView(zoom, zoomParams);
        }
        return footer;
    }

    private void updateZoomButton(Button button) {
        button.setText(zoomed ? "Fit" : "2\u00d7");
        button.setContentDescription(zoomed ? "Fit page to screen" : "Zoom in two times");
    }

    private void showJump(String unit, int count) {
        if (busy) return;
        if (preview != null && (unit.equals("sheet") || unit.equals("slide"))) {
            String[] titles = new String[count];
            for (int i = 0; i < count; i++) titles[i] = preview.sections.get(i).title;
            new AlertDialog.Builder(this).setTitle(unit.equals("sheet") ? "Worksheets" : "Slides")
                    .setSingleChoiceItems(titles, position, (dialog, index) -> {
                        dialog.dismiss();
                        goTo(index);
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("1 to " + count);
        input.setContentDescription(unit + " number");
        FrameLayout field = new FrameLayout(this);
        field.setPadding(dp(24), dp(5), dp(24), 0);
        field.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Go to " + unit)
                .setView(field).setNegativeButton("Cancel", null).setPositiveButton("Go", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int index;
                    try {
                        index = Integer.parseInt(input.getText().toString()) - 1;
                    } catch (NumberFormatException e) {
                        index = -1;
                    }
                    if (index < 0 || index >= count) {
                        input.setError("Enter a number from 1 to " + count);
                    } else {
                        dialog.dismiss();
                        goTo(index);
                    }
                }));
        dialog.show();
    }

    private void goTo(int index) {
        if (busy || index == position) return;
        if (preview != null) {
            if (index < 0 || index >= preview.sections.size()) return;
            position = index;
            zoomed = false;
            rememberPosition();
            showPreview();
        } else if (page != null && index >= 0 && index < pageCount) {
            busy = true;
            int token = requestId;
            int count = pageCount;
            int width = renderWidth();
            String name = documentName;
            worker.execute(() -> {
                if (token != requestId || pdf == null) return;
                try {
                    finishPdf(token, name, index, count, pdf.render(index, width, 2304));
                } catch (Exception e) {
                    closePdf();
                    showError(token, name, e);
                }
            });
        }
    }


    private void showLoading() {
        LinearLayout state = column();
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(24), dp(16), dp(24), dp(16));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.accent)));
        state.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView note = label("Opening document\u2026", 16, R.color.muted, false);
        note.setGravity(Gravity.CENTER);
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addWithTopMargin(state, note, 13);
        setBody(state);
    }

    private void showError(int token, String name, Exception error) {
        Log.w("DocsViewer", "Document operation failed", error);
        runOnUiThread(() -> {
            if (token != requestId) return;
            busy = false;
            clearPreview();
            page = null;
            pdfViewport = null;
            documentName = name;
            discardPendingGrant();
            readerToolbar("Unable to open");
            LinearLayout content = column();
            int side = readingMargin(460, 20);
            content.setPadding(side, dp(24), side, dp(24));
            LinearLayout panel = column();
            panel.setBackground(shape(color(R.color.surface), 20, true));
            panel.setPadding(dp(20), dp(19), dp(20), dp(20));
            content.addView(panel, new LinearLayout.LayoutParams(MATCH, WRAP));
            TextView title = label("Couldn\u2019t open this file", 22, R.color.ink, true);
            heading(title);
            panel.addView(title);
            addWithTopMargin(panel, label(errorMessage(error), 15, R.color.muted, false), 9);
            addWithTopMargin(panel, button("Choose another file", true, v -> pick()), 16);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
            setBody(scroll);
        });
    }

    private String errorMessage(Exception error) {
        if (error instanceof FileNotFoundException) {
            return "The file may have moved or been deleted. Try opening it again from the file picker.";
        }
        if (error instanceof SecurityException) {
            return "This file may be password protected or no longer accessible.";
        }
        if (error.getClass() == IOException.class && error.getMessage() != null) return error.getMessage();
        return "Check that the file is complete and in a supported format.";
    }

    @Override public void onBackPressed() {
        if (documentUri != null) returnHome();
        else super.onBackPressed();
    }

    private void returnHome() {
        requestId++;
        discardPendingGrant();
        documentUri = null;
        clearPreview();
        page = null;
        pdfViewport = null;
        worker.execute(this::closePdf);
        showHome();
    }

    private void discardPendingGrant() {
        if (pendingGrant == null) return;
        recent.release(pendingGrant);
        pendingGrant = null;
    }

    private void closePdf() {
        if (pdf == null) return;
        try {
            pdf.close();
        } finally {
            pdf = null;
        }
    }

    @Override protected void onDestroy() {
        requestId++;
        clearPreview();
        if (isFinishing()) discardPendingGrant();
        worker.execute(this::closePdf);
        worker.shutdown();
        super.onDestroy();
    }

    private String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some document providers only implement opening the file.
        }
        return uri.getLastPathSegment() == null ? "Document" : uri.getLastPathSegment();
    }

    private String typeLabel(String format) {
        if (format.equals("markdown") || format.equals("md")) return "MD";
        if (format.isEmpty() || format.length() > 5) return "FILE";
        return format.toUpperCase(Locale.ROOT);
    }

    private String formatName(String format) {
        switch (format) {
            case "md":
            case "markdown": return "Markdown";
            case "docx": return "Word document";
            case "xlsx": return "Excel spreadsheet";
            case "pptx": return "PowerPoint presentation";
            case "odt": return "OpenDocument text";
            case "ods": return "OpenDocument spreadsheet";
            case "odp": return "OpenDocument presentation";
            case "csv": return "CSV table";
            case "tsv": return "TSV table";
            case "htm":
            case "html": return "HTML document";
            case "json": return "JSON";
            case "xml": return "XML";
            case "java": return "Java source";
            case "kt": return "Kotlin source";
            case "js": return "JavaScript source";
            case "css": return "CSS source";
            case "log": return "Log file";
            default: return "Text document";
        }
    }

    private void setBody(View view) {
        body.removeAllViews();
        body.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private ImageView mark() {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.ic_docs);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private int renderWidth() {
        return Math.min(1600, Math.max(640, getResources().getDisplayMetrics().widthPixels * 3 / 2));
    }

    private int readingMargin(int width, int minimum) {
        int screen = getResources().getConfiguration().screenWidthDp;
        return dp(Math.max(minimum, (screen - width) / 2));
    }

    private int color(int resource) {
        return getColor(resource);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void addWithTopMargin(LinearLayout layout, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(margin);
        layout.addView(child, params);
    }

    private void heading(TextView text) {
        if (Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
    }

    private TextView label(CharSequence value, int size, int colorResource, boolean emphasized) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color(colorResource));
        if (emphasized) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private Button button(String caption, boolean primary, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(caption);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(color(primary ? R.color.on_accent : R.color.accent));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setStateListAnimator(null);
        button.setBackground(ripple(color(primary ? R.color.accent : R.color.tint), 13, false));
        button.setOnClickListener(click);
        return button;
    }

    private void setAvailable(View control, boolean available) {
        control.setEnabled(available);
        control.setAlpha(available ? 1f : 0.46f);
    }

    private GradientDrawable shape(int fill, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), color(R.color.border));
        return drawable;
    }

    private RippleDrawable ripple(int fill, int radius, boolean border) {
        return new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
                shape(fill, radius, border), null);
    }
}
