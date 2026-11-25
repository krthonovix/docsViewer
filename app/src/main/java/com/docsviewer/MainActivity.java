package com.docsviewer;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.xmlpull.v1.XmlPullParser;
import android.util.Xml;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    private static final int PICK = 42, LIMIT = 16 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root, bar; private TextView title, status; private FrameLayout content;
    private PdfRenderer pdf; private ParcelFileDescriptor descriptor; private File temp; private Bitmap bitmap;
    private int page = 0, generation = 0; private String name = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfff5f6f8);
        bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(12, 8, 12, 8); bar.setBackgroundColor(0xff17233a);
        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = new Button(this); open.setText("Open"); open.setOnClickListener(v -> pick()); bar.addView(open); root.addView(bar);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this); status.setGravity(Gravity.CENTER); status.setPadding(12, 8, 12, 12); root.addView(status);
        setContentView(root); home();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) open(getIntent().getData());
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (i.getData() != null) open(i.getData()); }
    private void home() { title.setText("Docs Viewer"); status.setText("Private, on-device reading"); TextView t = new TextView(this); t.setGravity(Gravity.CENTER); t.setTextSize(19); t.setPadding(32,32,32,32); t.setText("Your documents, in one place\n\nTap Open to choose a file\n\nPDF · DOCX · XLSX · PPTX · ODT · ODS · ODP · text · Markdown · CSV"); content.removeAllViews(); content.addView(t); }
    private void pick() { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request,result,data); if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) open(data.getData()); }
    private String filename(Uri uri) { try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null,null,null)) { if (c != null && c.moveToFirst()) return c.getString(0); } catch (Exception ignored) {} return uri.getLastPathSegment() == null ? "Document" : uri.getLastPathSegment(); }
    private void open(Uri uri) {
        int id = ++generation; name = filename(uri); title.setText(name); status.setText("Opening…"); content.removeAllViews();
        worker.execute(() -> { closePdf(); try {
            String lower = name.toLowerCase(Locale.ROOT); String mime = getContentResolver().getType(uri);
            if (lower.endsWith(".pdf") || "application/pdf".equals(mime)) {
                temp = File.createTempFile("document-", ".pdf", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temp)) { if (in == null) throw new IOException("Cannot open file"); byte[] b = new byte[32768]; int n; while ((n=in.read(b)) != -1) out.write(b,0,n); }
                descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY); pdf = new PdfRenderer(descriptor); page = 0; render(id);
            } else {
                String text; try (InputStream in = getContentResolver().openInputStream(uri)) { if (in == null) throw new IOException("Cannot open file");
                    if (lower.matches(".*\\.(docx|xlsx|pptx|odt|ods|odp)$")) text = office(in, lower);
                    else if (lower.matches(".*\\.(txt|md|markdown|csv|tsv|json|xml|html|htm|log|java|kt|js|css)$") || (mime != null && mime.startsWith("text/"))) text = new String(readLimited(in), StandardCharsets.UTF_8);
                    else throw new IOException("Unsupported format. Try PDF, DOCX, XLSX, PPTX, OpenDocument or a text file."); }
                final String result = text; runOnUiThread(() -> { if (id != generation) return; TextView view = new TextView(this); view.setTextIsSelectable(true); view.setTextColor(0xff202b3c); view.setTextSize(16); view.setPadding(24,24,24,24); view.setText(result.isEmpty() ? "No readable text found in this document." : result); HorizontalScrollView h = new HorizontalScrollView(this); ScrollView scroll = new ScrollView(this); scroll.addView(view); h.addView(scroll); content.removeAllViews(); content.addView(h); status.setText("Text view · formatting and images may differ"); });
            }
        } catch (Exception e) { error(id,e); } });
    }
    private void render(int id) {
        try { if (pdf == null || pdf.getPageCount() == 0) throw new IOException("PDF has no pages");
            int current = page, count = pdf.getPageCount(); Bitmap image;
            try (PdfRenderer.Page p = pdf.openPage(current)) { int width = Math.min(1600, Math.max(600, getResources().getDisplayMetrics().widthPixels)); int height = Math.max(1, Math.min(3000, (int)((long)width * p.getHeight() / p.getWidth()))); image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE); p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); }
            runOnUiThread(() -> { if (id != generation) { image.recycle(); return; } content.removeAllViews(); if (bitmap != null) bitmap.recycle(); bitmap = image;
                LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); ImageView iv = new ImageView(this); iv.setImageBitmap(image); iv.setAdjustViewBounds(true); ScrollView scroll = new ScrollView(this); scroll.addView(iv); layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
                LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); Button prev = new Button(this); prev.setText("Previous"); prev.setEnabled(current > 0); Button next = new Button(this); next.setText("Next"); next.setEnabled(current + 1 < count); TextView label = new TextView(this); label.setPadding(24,0,24,0); label.setText((current+1)+" / "+count); prev.setOnClickListener(v -> turn(-1)); next.setOnClickListener(v -> turn(1)); nav.addView(prev); nav.addView(label); nav.addView(next); layout.addView(nav); content.addView(layout); status.setText("PDF · page " + (current+1) + " of " + count); });
        } catch (Exception e) { error(id,e); }
    }
    private void turn(int delta) { int id = generation; status.setText("Rendering…"); worker.execute(() -> { if (id != generation || pdf == null) return; page = Math.max(0,Math.min(pdf.getPageCount()-1,page+delta)); render(id); }); }
    private void error(int id, Exception e) { runOnUiThread(() -> { if (id != generation) return; status.setText("Could not open document"); TextView t = new TextView(this); t.setPadding(32,32,32,32); t.setText("Unable to read this file.\n\n" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); content.removeAllViews(); content.addView(t); }); }
    private void closePdf() { try { if (pdf != null) pdf.close(); else if (descriptor != null) descriptor.close(); } catch (Exception ignored) {} pdf = null; descriptor = null; if (temp != null) temp.delete(); temp = null; }
    @Override protected void onDestroy() { ++generation; worker.execute(this::closePdf); worker.shutdown(); super.onDestroy(); }
    private static byte[] readLimited(InputStream in) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n, total=0; while ((n=in.read(b)) != -1) { total+=n; if (total > LIMIT) throw new IOException("Document content exceeds the 16 MB reading limit."); out.write(b,0,n); } return out.toByteArray(); }
    private static String office(InputStream input, String name) throws Exception {
        Map<String,byte[]> entries = new TreeMap<>(MainActivity::naturalCompare); int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) { ZipEntry e; while ((e=zip.getNextEntry()) != null) { String p=e.getName(); boolean wanted = p.equals("word/document.xml") || p.equals("content.xml") || p.equals("xl/sharedStrings.xml") || p.matches("xl/worksheets/sheet[0-9]+\\.xml") || p.matches("ppt/slides/slide[0-9]+\\.xml"); if (wanted) { byte[] data=readLimited(zip); total+=data.length; if (total>LIMIT) throw new IOException("Document content exceeds the 16 MB reading limit."); entries.put(p,data); } } }
        ArrayList<String> shared = new ArrayList<>(); if (entries.containsKey("xl/sharedStrings.xml")) shared = sharedStrings(entries.remove("xl/sharedStrings.xml"));
        StringBuilder out = new StringBuilder(); for (Map.Entry<String,byte[]> e : entries.entrySet()) { if (e.getKey().startsWith("ppt/")) out.append("Slide ").append(e.getKey().replaceAll(".*slide([0-9]+)\\.xml", "$1")).append("\n\n"); if (e.getKey().startsWith("xl/")) out.append("Sheet ").append(e.getKey().replaceAll(".*sheet([0-9]+)\\.xml", "$1")).append("\n\n"); extract(e.getValue(),out,shared,e.getKey()); out.append("\n\n"); }
        if (entries.isEmpty()) throw new IOException("No supported document content found. The file may be encrypted or damaged."); return out.toString().trim();
    }
    private static XmlPullParser parser(byte[] xml) throws Exception { XmlPullParser p=Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true); p.setInput(new ByteArrayInputStream(xml),"UTF-8"); return p; }
    private static ArrayList<String> sharedStrings(byte[] xml) throws Exception { ArrayList<String> list=new ArrayList<>(); XmlPullParser p=parser(xml); StringBuilder s=null; for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next()) { if(event==XmlPullParser.START_TAG && p.getName().equals("si")) s=new StringBuilder(); else if(event==XmlPullParser.START_TAG && p.getName().equals("t") && s!=null) s.append(p.nextText()); else if(event==XmlPullParser.END_TAG && p.getName().equals("si") && s!=null) { list.add(s.toString()); s=null; } } return list; }
    private static void extract(byte[] xml,StringBuilder out,ArrayList<String> shared,String path) throws Exception { XmlPullParser p=parser(xml); boolean sheet=path.startsWith("xl/"), sharedCell=false; for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next()) { String tag=p.getName(); if(event==XmlPullParser.START_TAG) { if(sheet && tag.equals("c")) sharedCell="s".equals(p.getAttributeValue(null,"t")); else if(sheet && tag.equals("v")) { String value=p.nextText(); if(sharedCell) try { value=shared.get(Integer.parseInt(value)); } catch(Exception ignored) {} out.append(value).append('\t'); } else if(tag.equals("t")) out.append(p.nextText()); else if(tag.equals("tab")) out.append('\t'); else if(tag.equals("br") || tag.equals("line-break")) out.append('\n'); } else if(event==XmlPullParser.TEXT && path.equals("content.xml")) out.append(p.getText()); else if(event==XmlPullParser.END_TAG && (tag.equals("p") || tag.equals("row") || tag.equals("table-row"))) out.append('\n'); else if(event==XmlPullParser.END_TAG && tag.equals("table-cell")) out.append('\t'); } }
    private static int naturalCompare(String a,String b) { String aa = a.replaceAll("([0-9]+)(?=\\.xml$)", "0000000000$1"); String bb = b.replaceAll("([0-9]+)(?=\\.xml$)", "0000000000$1"); int ai=aa.lastIndexOf("0000000000"), bi=bb.lastIndexOf("0000000000"); if (ai >= 0 && bi >= 0) { String ap=aa.substring(0,ai), bp=bb.substring(0,bi); int c=ap.compareTo(bp); if(c != 0) return c; String an=aa.substring(ai+10,aa.length()-4), bn=bb.substring(bi+10,bb.length()-4); c=Integer.compare(an.length(),bn.length()); if(c != 0) return c; return an.compareTo(bn); } return a.compareTo(b); }
}

