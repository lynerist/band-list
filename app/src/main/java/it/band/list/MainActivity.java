package it.band.list;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B1220"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E1626"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1220"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Annulla", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Scegli il file di backup"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack ? window.handleBack() : false)", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCb != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                res = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(res);
            fileCb = null;
        }
    }

    private static class PdfWriter {
        static final int W = 595, H = 842, M = 40;
        final PdfDocument doc = new PdfDocument();
        final boolean dry;
        PdfDocument.Page page;
        Canvas c;
        int n = 0;
        float y;

        PdfWriter(boolean dry) { this.dry = dry; }

        void newPage() {
            if (page != null) doc.finishPage(page);
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++n).create());
            c = page.getCanvas();
            y = M;
        }

        void text(String text, Paint p, float gapAfter) {
            if (!dry && page == null) newPage();
            float maxW = W - 2 * M;
            int len = text.length(), start = 0;
            while (start < len) {
                int cnt = p.breakText(text, start, len, true, maxW, null);
                if (cnt < 1) cnt = 1;
                if (start + cnt < len) {
                    int sp = text.lastIndexOf(' ', start + cnt);
                    if (sp > start) cnt = sp - start;
                }
                float lh = p.getTextSize() * 1.25f;
                if (!dry && y + lh > H - M) newPage();
                y += lh;
                if (!dry) c.drawText(text.substring(start, start + cnt).trim(), W / 2f, y - p.getTextSize() * 0.2f, p);
                start += cnt;
                while (start < len && text.charAt(start) == ' ') start++;
            }
            y += gapAfter;
        }

        void space(float s) { y += s; }
    }

    private static Paint mkPaint(float size, boolean bold, boolean italic, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(color);
        int style = bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        return p;
    }

    private static void renderSetlist(PdfWriter w, JSONObject o, float sz, float extra) throws Exception {
        JSONArray items = o.getJSONArray("items");
        JSONArray between = o.getJSONArray("between");
        Paint title = mkPaint(Math.min(40f, sz * 1.15f), true, false, Color.BLACK);
        Paint date = mkPaint(Math.max(11f, sz * 0.5f), false, false, Color.DKGRAY);
        Paint song = mkPaint(sz, true, false, Color.BLACK);
        Paint small = mkPaint(Math.max(10f, sz * 0.5f), false, true, Color.GRAY);

        w.text(o.getString("title"), title, 2);
        String d = o.optString("date", "");
        if (d.length() > 0) w.text(d, date, 0);
        w.space(sz * 0.9f);

        for (int i = 0; i < items.length(); i++) {
            w.text((i + 1) + ".  " + items.getString(i), song, 3);
            if (i < between.length()) {
                JSONObject b = between.getJSONObject(i);
                String note = b.optString("note", "").trim();
                if (b.optBoolean("segue", false)) {
                    w.text("\u2193 attacco diretto", small, 2);
                } else if (note.length() > 0) {
                    w.text("\u2014 " + note, small, 2);
                }
                w.space(sz * 0.3f + extra);
            }
        }
    }

    private class Bridge {
        @JavascriptInterface
        public String savePdf(String name, String json) {
            try {
                JSONObject o = new JSONObject(json);
                int n = o.getJSONArray("items").length();

                float chosen = 16f;
                float height = 0f;
                boolean fits = false;
                for (float sz = 40f; sz >= 16f; sz -= 1f) {
                    PdfWriter dry = new PdfWriter(true);
                    dry.y = 0;
                    renderSetlist(dry, o, sz, 0f);
                    if (dry.y <= PdfWriter.H - 2 * PdfWriter.M) {
                        chosen = sz; height = dry.y; fits = true;
                        break;
                    }
                }

                PdfWriter w = new PdfWriter(false);
                w.newPage();
                float extra = 0f;
                if (fits) {
                    float leftover = PdfWriter.H - 2 * PdfWriter.M - height;
                    extra = Math.min(leftover / Math.max(n, 1), chosen * 0.8f);
                    float used = height + extra * Math.max(n - 1, 0);
                    w.y += Math.max(0f, (PdfWriter.H - 2 * PdfWriter.M - used) / 2f);
                }
                renderSetlist(w, o, chosen, extra);
                if (w.page != null) w.doc.finishPage(w.page);

                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                OutputStream out = getContentResolver().openOutputStream(u);
                w.doc.writeTo(out);
                out.close();
                w.doc.close();

                try {
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(u, "application/pdf");
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(view);
                } catch (Exception ignored) {
                }
                return "ok";
            } catch (Exception e) {
                return "err";
            }
        }

        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                OutputStream o = getContentResolver().openOutputStream(u);
                o.write(content.getBytes("UTF-8"));
                o.close();
                return "ok";
            } catch (Exception e) {
                return "err";
            }
        }
    }
}
