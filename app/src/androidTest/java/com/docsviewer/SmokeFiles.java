package com.docsviewer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SmokeFiles extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if ("sample.pdf".equals(name) || "broken.pdf".equals(name)) return "application/pdf";
        if (name != null && name.matches("sample\\.(docx|xlsx|pptx|odt|ods|odp|txt|md|markdown|csv|tsv|html|htm|json|xml|java|kt|js|css|log)")) {
            return "application/octet-stream";
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] arguments, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment()});
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || getType(uri) == null) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        File file = new File(getContext().getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            switch (name) {
                case "sample.pdf": {
                    PdfDocument pdf = new PdfDocument();
                    try {
                        for (int number = 1; number <= 2; number++) {
                            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(300, 600, number).create();
                            PdfDocument.Page page = pdf.startPage(info);
                            page.getCanvas().drawText("Page " + number, 40, 100, new Paint());
                            pdf.finishPage(page);
                        }
                        pdf.writeTo(output);
                    } finally {
                        pdf.close();
                    }
                    break;
                }
                case "broken.pdf":
                    output.write("Invalid PDF".getBytes(StandardCharsets.UTF_8));
                    break;
                default:
                    SmokeDocuments.write(name, output);
                    break;
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException(name);
            failure.initCause(error);
            throw failure;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }
}
