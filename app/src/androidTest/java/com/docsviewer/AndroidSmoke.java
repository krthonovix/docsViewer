package com.docsviewer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

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

            Activity text = open("sample.txt");
            await(text, "Text reader sample", false);
            close(text);

            Activity word = open("sample.docx");
            await(word, "Office preview sample", false);
            close(word);

            Activity invalid = open("broken.pdf");
            await(invalid, "Choose another file", false);
            await(invalid, "Back to files", true);
            await(invalid, "Browse files", false);
            close(invalid);

            result.putString("stream", "\nReader smoke tests passed: PDF navigation, zoom, text, DOCX and corrupt file recovery.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\n" + Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
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
