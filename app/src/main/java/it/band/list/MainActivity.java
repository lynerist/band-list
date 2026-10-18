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
        web = new WebView(this);
        setContentView(web);
        applyBars(getSharedPreferences("prefs", MODE_PRIVATE).getString("theme", "dark"));

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
                        .setNegativeButton("Cancel", (d, w) -> r.cancel())
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
                    startActivityForResult(Intent.createChooser(i, "Choose the backup file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void applyBars(String theme) {
        boolean light = "light".equals(theme);
        int bg = Color.parseColor(light ? "#F3F6FB" : "#0B1220");
        int nav = Color.parseColor(light ? "#FFFFFF" : "#0E1626");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(nav);
        if (web != null) web.setBackgroundColor(bg);
        int flags = light ? (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
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

        void rule(float width, float thickness, int color) {
            if (!dry && page == null) newPage();
            if (!dry) {
                Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                lp.setColor(color);
                lp.setStrokeWidth(thickness);
                c.drawLine(W / 2f - width / 2f, y, W / 2f + width / 2f, y, lp);
            }
        }
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
        Paint title = mkPaint(Math.min(44f, sz * 1.3f), true, false, Color.BLACK);
        title.setLetterSpacing(0.12f);
        Paint date = mkPaint(Math.max(11f, sz * 0.5f), false, false, Color.DKGRAY);
        Paint song = mkPaint(sz, true, false, Color.BLACK);
        Paint small = mkPaint(Math.max(10f, sz * 0.5f), false, true, Color.GRAY);

        w.text(o.getString("title").toUpperCase(java.util.Locale.ENGLISH), title, 4);
        String d = o.optString("date", "");
        if (d.length() > 0) w.text(d, date, 0);
        w.space(sz * 0.8f);
        w.rule(120f, 1.5f, Color.GRAY);
        w.space(sz * 1.5f);

        for (int i = 0; i < items.length(); i++) {
            w.text(items.getString(i), song, 3);
            if (i < between.length()) {
                JSONObject b = between.getJSONObject(i);
                String note = b.optString("note", "").trim();
                if (b.optBoolean("segue", false)) {
                    w.text("\u2193 segue", small, 2);
                } else if (note.length() > 0) {
                    w.text("\u2014 " + note, small, 2);
                }
                w.space(sz * 0.3f + extra);
            }
        }
    }

    private void drawLyrics(PdfWriter w, String title, String lyrics, float sz, boolean dry) {
        Paint tp = mkPaint(sz * 1.5f, true, false, Color.BLACK);
        tp.setTextAlign(Paint.Align.LEFT);
        Paint bp = mkPaint(sz, false, false, Color.BLACK);
        bp.setTextAlign(Paint.Align.LEFT);
        float maxW = PdfWriter.W - 2 * PdfWriter.M;
        if (!dry && w.page == null) w.newPage();
        w.y = PdfWriter.M;
        String[] tl = wrap(title, tp, maxW);
        for (String s : tl) { w.y += tp.getTextSize() * 1.25f; if (!dry) w.c.drawText(s, PdfWriter.M, w.y, tp); }
        w.y += sz * 1.2f;
        float lh = sz * 1.3f;
        for (String para : lyrics.replace("\r", "").split("\n", -1)) {
            String[] ls = para.isEmpty() ? new String[]{""} : wrap(para, bp, maxW);
            for (String s : ls) {
                if (w.y + lh > PdfWriter.H - PdfWriter.M) { if (dry) w.n++; else w.newPage(); if (dry) w.y = PdfWriter.M; }
                w.y += lh;
                if (!dry) w.c.drawText(s, PdfWriter.M, w.y, bp);
            }
        }
    }

    private static String[] wrap(String text, Paint p, float maxW) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int len = text.length(), start = 0;
        while (start < len) {
            int cnt = p.breakText(text, start, len, true, maxW, null);
            if (cnt < 1) cnt = 1;
            if (start + cnt < len) {
                int sp = text.lastIndexOf(' ', start + cnt);
                if (sp > start) cnt = sp - start;
            }
            out.add(text.substring(start, start + cnt).trim());
            start += cnt;
            while (start < len && text.charAt(start) == ' ') start++;
        }
        return out.toArray(new String[0]);
    }

    private class Bridge {
        @JavascriptInterface
        public void setTheme(final String theme) {
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("theme", theme).apply();
            runOnUiThread(() -> applyBars(theme));
        }

        @JavascriptInterface
        public String saveLyricsPdf(String name, String json) {
            try {
                JSONObject o = new JSONObject(json);
                String title = o.getString("title"), lyrics = o.getString("lyrics");
                float chosen = 11f;
                for (float sz = 14f; sz >= 11f; sz -= 1f) {
                    PdfWriter dry = new PdfWriter(true);
                    drawLyrics(dry, title, lyrics, sz, true);
                    chosen = sz;
                    if (dry.n == 0) break;
                }
                PdfWriter w = new PdfWriter(false);
                drawLyrics(w, title, lyrics, chosen, false);
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
