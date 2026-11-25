package com.docsviewer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

final class PdfDocument implements Closeable {
    private final PdfRenderer renderer;
    private final File copy;

    private PdfDocument(PdfRenderer renderer, File copy) {
        this.renderer = renderer;
        this.copy = copy;
    }

    static PdfDocument open(ContentResolver resolver, Uri uri, File cache, BooleanSupplier cancelled)
            throws IOException {
        ParcelFileDescriptor source = resolver.openFileDescriptor(uri, "r");
        if (source == null) throw new IOException("The file could not be opened.");
        boolean seekable = true;
        try {
            Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
        } catch (ErrnoException e) {
            seekable = false;
        }
        if (seekable) return fromDescriptor(source, null);

        // Read the original pipe rather than opening a second download from the provider.
        File copy = null;
        boolean opened = false;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source)) {
            copy = File.createTempFile("document-", ".pdf", cache);
            try (FileOutputStream output = new FileOutputStream(copy)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException();
                    output.write(buffer, 0, count);
                }
            }
            PdfDocument pdf = fromDescriptor(ParcelFileDescriptor.open(copy,
                    ParcelFileDescriptor.MODE_READ_ONLY), copy);
            opened = true;
            return pdf;
        } finally {
            if (!opened && copy != null) copy.delete();
        }
    }

    private static PdfDocument fromDescriptor(ParcelFileDescriptor source, File copy) throws IOException {
        try {
            return new PdfDocument(new PdfRenderer(source), copy);
        } catch (IOException | RuntimeException e) {
            try {
                source.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    int pageCount() {
        return renderer.getPageCount();
    }

    Bitmap render(int index, int widthLimit, int heightLimit) throws IOException {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            double scale = Math.min((double) widthLimit / page.getWidth(),
                    (double) heightLimit / page.getHeight());
            int width = Math.max(1, (int) (page.getWidth() * scale));
            int height = Math.max(1, (int) (page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } catch (RuntimeException e) {
                bitmap.recycle();
                throw new IOException("This PDF page could not be drawn.", e);
            }
        }
    }

    @Override public void close() {
        try {
            renderer.close();
        } finally {
            if (copy != null) copy.delete();
        }
    }
}
