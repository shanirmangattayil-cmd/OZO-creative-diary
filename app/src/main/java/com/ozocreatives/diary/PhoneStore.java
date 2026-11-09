package com.ozocreatives.diary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.WebResourceResponse;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 1. Loads the newest app screen from the owner's GitHub Pages site (HTTPS), with an offline copy.
 * 2. Keeps a copy of every photo, video and file in Download/Ozo Diary so nothing is lost if the app is removed.
 * 3. Reads that folder back (after the user picks it) to restore everything.
 */
class PhoneStore {
    static final String REMOTE = "https://shanirmangattayil-cmd.github.io/OZO-creative-diary/app.html";
    static final String ROOT = "Ozo Diary";
    static final String MARK = "<!--ozo-app-->";

    // ---------- newest app screen ----------
    static WebResourceResponse index(Context c) {
        File cache = new File(c.getFilesDir(), "app.html");
        byte[] fresh = null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(REMOTE + "?t=" + System.currentTimeMillis()).openConnection();
            h.setConnectTimeout(3500);
            h.setReadTimeout(8000);
            h.setUseCaches(false);
            if (h.getResponseCode() == 200) {
                byte[] b = readAll(h.getInputStream());
                if (b.length > 50000 && new String(b, StandardCharsets.UTF_8).contains(MARK)) {
                    fresh = b;
                    File tmp = new File(c.getFilesDir(), "app.html.tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                    if (!tmp.renameTo(cache)) tmp.delete();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
        InputStream in;
        try {
            if (fresh != null) in = new ByteArrayInputStream(fresh);
            else if (cache.exists() && cache.length() > 50000) in = new FileInputStream(cache);
            else in = c.getAssets().open("index.html");
        } catch (IOException e) {
            return null;
        }
        WebResourceResponse r = new WebResourceResponse("text/html", "utf-8", in);
        Map<String, String> hd = new HashMap<>();
        hd.put("Cache-Control", "no-store");
        r.setResponseHeaders(hd);
        return r;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    // ---------- safety copy in Download/Ozo Diary ----------
    static final Map<String, OutputStream> open = new HashMap<>();
    static final Map<String, Uri> uris = new HashMap<>();
    static final Map<String, File> legacy = new HashMap<>();

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\:*?\"<>|\\x00-\\x1f]", "").replace("..", "").trim();
    }

    static synchronized String begin(Context c, String rel, String name, String mime) {
        try {
            rel = clean(rel);
            name = clean(name).replace("/", "_");
            if (name.isEmpty()) return "";
            String tok = UUID.randomUUID().toString();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + rel + "/");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "";
                OutputStream o = c.getContentResolver().openOutputStream(u, "w");
                if (o == null) { c.getContentResolver().delete(u, null, null); return ""; }
                open.put(tok, new BufferedOutputStream(o, 262144));
                uris.put(tok, u);
            } else {
                File d = new File(c.getExternalFilesDir(null), ROOT + "/" + rel);
                d.mkdirs();
                File f = new File(d, name);
                open.put(tok, new BufferedOutputStream(new FileOutputStream(f), 262144));
                legacy.put(tok, f);
            }
            return tok;
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized boolean append(String tok, String b64) {
        OutputStream o = open.get(tok);
        if (o == null) return false;
        try {
            o.write(Base64.decode(b64, Base64.DEFAULT));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized boolean end(Context c, String tok, boolean ok) {
        OutputStream o = open.remove(tok);
        Uri u = uris.remove(tok);
        File f = legacy.remove(tok);
        try { if (o != null) o.close(); } catch (Exception e) { ok = false; }
        try {
            if (u != null) {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    c.getContentResolver().update(u, v, null, null);
                } else {
                    c.getContentResolver().delete(u, null, null);
                }
            } else if (f != null && !ok) {
                f.delete();
            }
        } catch (Exception e) {
            return false;
        }
        return ok;
    }

    static boolean delete(Context c, String rel, String name) {
        try {
            rel = clean(rel);
            name = clean(name).replace("/", "_");
            if (Build.VERSION.SDK_INT >= 29) {
                String path = Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + rel + "/";
                return c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{path, name}) > 0;
            }
            return new File(new File(c.getExternalFilesDir(null), ROOT + "/" + rel), name).delete();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- restore from a folder the user picks ----------
    static final List<String[]> restoreList = new ArrayList<>();

    static synchronized String scan(Context c, Uri tree) {
        restoreList.clear();
        JSONArray out = new JSONArray();
        try {
            walk(c, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, out);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    static void walk(Context c, Uri tree, String docId, String rel, int depth, JSONArray out) throws Exception {
        if (depth > 4) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                long size = cur.isNull(3) ? 0 : cur.getLong(3);
                long mod = cur.isNull(4) ? 0 : cur.getLong(4);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(c, tree, id, rel.isEmpty() ? name : rel + "/" + name, depth + 1, out);
                    continue;
                }
                int idx = restoreList.size();
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                restoreList.add(new String[]{doc.toString(), rel, name, mime == null ? "" : mime});
                JSONObject o = new JSONObject();
                o.put("i", idx);
                o.put("rel", rel);
                o.put("name", name);
                o.put("mime", mime == null ? "" : mime);
                o.put("size", size);
                o.put("mod", mod);
                out.put(o);
            }
        }
    }

    static WebResourceResponse serve(Context c, String idxStr) {
        try {
            String[] e;
            synchronized (PhoneStore.class) { e = restoreList.get(Integer.parseInt(idxStr)); }
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(e[0]));
            WebResourceResponse r = new WebResourceResponse(e[3].isEmpty() ? "application/octet-stream" : e[3], null, in);
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-store");
            r.setResponseHeaders(hd);
            return r;
        } catch (Exception ex) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }
}
