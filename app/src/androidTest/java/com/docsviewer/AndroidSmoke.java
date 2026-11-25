package com.docsviewer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AndroidSmoke extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity pdf = open("sample.pdf");
            await(pdf, "PDF page 1", false);
            await(pdf, "Zoom in two times", true);
            await(pdf, "Fit page to screen", true);
            await(pdf, "Next page", true);
            await(pdf, "PDF page 2", false);
            await(pdf, "Back to files", true);
            await(pdf, "Browse files", false);
            close(pdf);

            preview("docx", "Word heading", "<h1", "font-weight:bold;", "<table", "Word cell", "alt=\"Embedded logo\"");
            preview("odt", "ODT heading", "<h2", "<ul", "ODT item</p></li>");
            Preview sheets = markup("xlsx", "class=\"grid\"", "colspan=\"3\"", "rowspan=\"3\"", "Budget grid");
            if (!"Budget".equals(sheets.sections.get(0).title) || !"Totals".equals(sheets.sections.get(1).title)) {
                throw new AssertionError("XLSX sheet names or order were lost");
            }
            Activity workbook = open("sample.xlsx");
            await(workbook, "Budget", false);
            awaitWeb(workbook);
            awaitContent(workbook, "Budget grid");
            screenshot(workbook, "xlsx");
            await(workbook, "Next sheet", true);
            await(workbook, "Totals", false);
            awaitWeb(workbook);
            close(workbook);
            preview("ods", "ODS cell", "class=\"grid\"", "scope=\"col\">C", "ODS cell");
            preview("pptx", "PowerPoint slide", "<svg viewBox", "<foreignObject x=", "PowerPoint slide",
                    "font-weight:bold;", "background-color:#17654F;", "border-radius:50%;");
            preview("odp", "ODP slide", "<svg viewBox", "<foreignObject x=", "ODP slide");
            preview("md", "Markdown heading", "<h1>Markdown heading", "<strong>Bold", "<del>deleted",
                    "type=\"checkbox\"", "<table");
            preview("markdown", null, "<blockquote>");
            preview("csv", "Comma, kept", "class=\"grid\"", "Comma, kept\nnext line", "<td>10</td>");
            preview("tsv", null, "<td>Item</td><td>20</td>");
            Preview html = markup("html", "<h1>HTML heading", "<table>", "HTML cell");
            if (html.sections.get(0).html.contains("<script") || html.sections.get(0).html.contains("onerror")
                    || html.sections.get(0).html.contains("https://example.invalid")) {
                throw new AssertionError("Active HTML was included in the preview");
            }
            preview("html", "HTML heading", "<article>");
            preview("htm", null, "<table>");
            preview("json", null, "class=\"source\"", "tok-string", "tok-number", "JSON sample");
            preview("xml", null, "class=\"source\"", "tok-tag", "&lt;entry");
            preview("java", null, "tok-keyword", "Example");
            preview("kt", null, "tok-keyword", "tok-number");
            preview("js", null, "tok-keyword", "tok-string");
            preview("css", null, "class=\"source\"", "#17654f");
            preview("log", null, "<th aria-hidden=\"true\">2", "INFO Second line");
            preview("txt", "Text reader sample", "class=\"plain\"", "    indented text");

            Activity invalid = open("broken.pdf");
            await(invalid, "Choose another file", false);
            await(invalid, "Back to files", true);
            await(invalid, "Browse files", false);
            close(invalid);

            result.putString("stream", "\nReader smoke tests passed: all 21 file extensions, "
                    + "PDF navigation, formatted content and corrupt PDF recovery.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\n" + Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void preview(String extension, String content, String... expected) throws Exception {
        markup(extension, expected);
        Activity activity = open("sample." + extension);
        awaitWeb(activity);
        if (content != null) awaitContent(activity, content);
        if (extension.equals("docx")) {
            assertDom(activity, "Array.from(document.images).some(image => image.naturalWidth === 160)",
                    "The embedded Word image did not render");
        }
        if (extension.equals("pptx") || extension.equals("odp")) {
            assertDom(activity, "document.querySelector('foreignObject').getBoundingClientRect().width > 0",
                    "The slide shape did not have a layout");
        }
        if (extension.matches("docx|odt|ods|pptx|odp|md|csv|html")) screenshot(activity, extension);
        if (extension.equals("pptx") || extension.equals("odp")) {
            await(activity, "Zoom in two times", true);
            await(activity, "Fit page to screen", true);
        }
        close(activity);
    }

    private void screenshot(Activity activity, String extension) throws IOException, InterruptedException {
        if (Build.VERSION.SDK_INT != 35) return;
        CountDownLatch drawn = new CountDownLatch(1);
        runOnMainSync(() -> {
            WebView web = findWebView(activity.getWindow().getDecorView());
            web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    drawn.countDown();
                }
            });
        });
        if (!drawn.await(5, TimeUnit.SECONDS)) throw new IOException("The preview did not finish drawing.");
        SystemClock.sleep(200);
        Bitmap screen = getUiAutomation().takeScreenshot();
        if (screen == null) throw new IOException("Could not capture the " + extension + " preview");
        try (FileOutputStream image = new FileOutputStream(
                new File(getTargetContext().getCacheDir(), "preview-" + extension + ".png"))) {
            if (!screen.compress(Bitmap.CompressFormat.PNG, 100, image)) {
                throw new IOException("Could not save the " + extension + " preview");
            }
        } finally {
            screen.recycle();
        }
    }

    private Preview markup(String extension, String... expected) throws Exception {
        ByteArrayOutputStream fixture = new ByteArrayOutputStream();
        SmokeDocuments.write("sample." + extension, fixture);
        Preview document = DocumentText.read(new ByteArrayInputStream(fixture.toByteArray()), extension);
        String markup = document.sections.get(0).html;
        for (String text : expected) {
            if (!markup.contains(text)) throw new AssertionError(extension + " preview missing " + text);
        }
        return document;
    }

    private void awaitWeb(Activity activity) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                WebView web = findWebView(decor);
                ready.set(web != null && web.getContentHeight() > 0 && web.getProgress() == 100
                        && find(decor, "Drawing preview") == null);
            });
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("The formatted view did not render " + activity.getIntent().getData());
    }

    private void awaitContent(Activity activity, String content) throws InterruptedException {
        assertDom(activity, "document.body.textContent.includes(" + JSONObject.quote(content) + ")",
                "The rendered preview is missing " + content);
    }

    private void assertDom(Activity activity, String expression, String message) throws InterruptedException {
        AtomicReference<WebView> view = new AtomicReference<>();
        runOnMainSync(() -> view.set(findWebView(activity.getWindow().getDecorView())));
        WebView web = view.get();
        AtomicBoolean wasDisabled = new AtomicBoolean();
        runOnMainSync(() -> {
            wasDisabled.set(!web.getSettings().getJavaScriptEnabled());
            // Only the instrumentation enables this to inspect the trusted fixture's DOM.
            web.getSettings().setJavaScriptEnabled(true);
        });
        try {
            if (!wasDisabled.get()) throw new AssertionError("The app enabled JavaScript in a preview");
            for (int attempt = 0; attempt < 10; attempt++) {
                AtomicReference<String> result = new AtomicReference<>();
                CountDownLatch evaluated = new CountDownLatch(1);
                runOnMainSync(() -> web.evaluateJavascript(expression, value -> {
                    result.set(value);
                    evaluated.countDown();
                }));
                if (evaluated.await(2, TimeUnit.SECONDS) && "true".equals(result.get())) return;
                SystemClock.sleep(150);
            }
            throw new AssertionError(message);
        } finally {
            runOnMainSync(() -> web.getSettings().setJavaScriptEnabled(false));
        }
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView child = findWebView(group.getChildAt(index));
                if (child != null) return child;
            }
        }
        return null;
    }

    private Activity open(String name) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.docsviewer.smoke.files/" + name));
        view.setClassName(getTargetContext().getPackageName(), MainActivity.class.getName());
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivitySync(view);
    }

    private void close(Activity activity) {
        runOnMainSync(activity::finish);
        waitForIdleSync();
    }

    private void await(Activity activity, String label, boolean click) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        AtomicBoolean matched = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                View match = find(activity.getWindow().getDecorView(), label);
                if (match != null && match.isShown() && (!click || match.isEnabled())) {
                    matched.set(!click || match.performClick());
                }
            });
            if (matched.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + label);
    }

    private static View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                || (view instanceof TextView && label.contentEquals(((TextView) view).getText()))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
