package com.ozocreatives.diary;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    static final int PICK = 41;
    WebView web;
    ValueCallback<Uri[]> pending;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                java.util.ArrayList<String> mimes = new java.util.ArrayList<>();
                boolean anyType = false;
                String[] acc = p.getAcceptTypes();
                if (acc != null) for (String a : acc) {
                    if (a == null) continue;
                    for (String part : a.split(",")) {
                        String t = part.trim();
                        if (t.isEmpty()) continue;
                        if (t.startsWith("image/") || t.startsWith("video/")) mimes.add(t); else anyType = true;
                    }
                }
                if (mimes.isEmpty()) anyType = true;
                boolean images = anyType;
                for (String m : mimes) if (m.startsWith("image/")) images = true;
                Intent cam = images ? cameraIntent() : null;
                try {
                    if (p.isCaptureEnabled() && cam != null) { startActivityForResult(cam, PICK); return true; }
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    if (!anyType) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    Intent chooser = Intent.createChooser(i, anyType ? "Choose a file" : "Choose photos or videos");
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    startActivityForResult(chooser, PICK);
                } catch (ActivityNotFoundException e) { pending = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "OzoNative");
        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
    }

    File cameraFile;
    Uri cameraUri;

    /** Camera intent that saves the photo into the app's cache, so it can be handed to the page. */
    Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera"); dir.mkdirs();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            cameraFile = new File(dir, "ozo-photo-" + stamp + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, "com.ozocreatives.diary.files", cameraFile);
            Intent c = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            c.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            c.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.setClipData(ClipData.newRawUri("photo", cameraUri));
            return c;
        } catch (Exception e) { cameraFile = null; cameraUri = null; return null; }
    }

    void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try { startActivity(i); return; }
                catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb))); return; }
                    throw e;
                }
            }
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || pending == null) { super.onActivityResult(req, res, data); return; }
        Uri[] out = null;
        if (res == RESULT_OK) {
            if (data != null) {
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) { out = new Uri[cd.getItemCount()]; for (int i = 0; i < cd.getItemCount(); i++) out[i] = cd.getItemAt(i).getUri(); }
                else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
            if (cameraFile != null && cameraFile.exists() && cameraFile.length() > 0
                    && (out == null || (out.length == 1 && cameraUri.equals(out[0])))) out = new Uri[]{cameraUri};
            else if (out != null && out.length == 1 && cameraUri != null && cameraUri.equals(out[0])
                    && (cameraFile == null || !cameraFile.exists() || cameraFile.length() == 0)) out = null;
        }
        cameraFile = null;
        pending.onReceiveValue(out);
        pending = null;
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.ozoBack?window.ozoBack():false", v -> { if (!"true".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); web.saveState(b); }

    boolean hasAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        String me = new ComponentName(this, InboxListener.class).flattenToString();
        for (String part : flat.split(":")) if (me.equals(part)) return true;
        return false;
    }

    class Bridge {
        @JavascriptInterface public boolean isLite() { return BuildConfig.LITE; }
        @JavascriptInterface public int versionCode() { return BuildConfig.VERSION_CODE; }
        @JavascriptInterface public String getInbox() { return InboxStore.load(MainActivity.this).toString(); }
        @JavascriptInterface public boolean hasAccess() { return MainActivity.this.hasAccess(); }
        @JavascriptInterface public void markRead(String json) { try { InboxStore.markRead(MainActivity.this, new JSONObject(json)); } catch (Exception ignored) { } }
        @JavascriptInterface public void clearRead() { InboxStore.clearRead(MainActivity.this); }
        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> { try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); } });
        }
        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> { ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Ozo", text)); });
        }
        /** Keeps a copy of all diary data in Download/Ozo Diary so it survives reinstalling the app. */
        @JavascriptInterface public boolean saveBackup(String json) {
            try {
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    String rel = Environment.DIRECTORY_DOWNLOADS + "/Ozo Diary/";
                    Uri target = null;
                    try (Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                            new String[]{"ozo-diary-backup.json", rel}, null)) {
                        if (c != null && c.moveToFirst()) target = Uri.withAppendedPath(coll, String.valueOf(c.getLong(0)));
                    }
                    if (target == null) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, "ozo-diary-backup.json");
                        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                        target = cr.insert(coll, v);
                    }
                    if (target == null) return false;
                    try (OutputStream o = cr.openOutputStream(target, "wt")) { o.write(data); }
                    return true;
                }
                File d = new File(getExternalFilesDir(null), "backup"); d.mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(d, "ozo-diary-backup.json"))) { o.write(data); }
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void open(String url) { runOnUiThread(() -> openExternal(url)); }
        @JavascriptInterface public void shareText(String text) {
            runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(i, "Share")); });
        }
        @JavascriptInterface public boolean shareFile(String name, String mime, String b64) {
            try {
                File dir = new File(getCacheDir(), "share"); dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^\\w.\\- ]", "_"));
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(b64, Base64.DEFAULT)); }
                Uri u = FileProvider.getUriForFile(MainActivity.this, "com.ozocreatives.diary.files", f);
                Intent i = new Intent(Intent.ACTION_SEND); i.setType(mime); i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(i, "Share")));
                return true;
            } catch (Exception e) { return false; }
        }
    }
}
