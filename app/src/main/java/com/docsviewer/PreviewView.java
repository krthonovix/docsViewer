package com.docsviewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class PreviewView extends WebView {
    private static final String POLICY = "default-src 'none'; "
            + "img-src https://docs-viewer.invalid data:; style-src 'unsafe-inline'; "
            + "base-uri 'none'; form-action 'none'; object-src 'none'";
    private final String css;

    @SuppressLint("SetJavaScriptEnabled")
    PreviewView(Context context, Map<String, Preview.Asset> assets, Runnable rendererStopped)
            throws IOException {
        super(context);
        css = stylesheet(context);
        setBackgroundColor(context.getColor(R.color.surface));
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(Math.round(context.getResources().getConfiguration().fontScale * 100));
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Preview.Asset asset = assets.get(request.getUrl().toString());
                if (asset == null) {
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse(asset.mime, null, new ByteArrayInputStream(asset.bytes));
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                rendererStopped.run();
                return true;
            }
        });
    }

    void display(Preview.Section section, String format) {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = mode == Configuration.UI_MODE_NIGHT_YES;
        String theme = (dark ? "dark " : "") + (format.matches("docx|odt|xlsx|ods|pptx|odp") ? "office" : "");
        String page = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + POLICY + "\">"
                + "<style>" + css + "</style></head><body class=\"" + theme + "\">"
                + section.html + "</body></html>";
        loadDataWithBaseURL(Preview.ORIGIN, page, "text/html", "UTF-8", null);
    }

    private static String stylesheet(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("preview.css")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
