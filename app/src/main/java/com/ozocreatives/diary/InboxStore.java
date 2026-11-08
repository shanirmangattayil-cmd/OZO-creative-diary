package com.ozocreatives.diary;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small JSON file that keeps the latest notifications for the Inbox. */
public class InboxStore {
    static final int KEEP = 800;

    static synchronized JSONArray load(Context c) {
        try {
            File f = new File(c.getFilesDir(), "inbox.json");
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void save(Context c, JSONArray a) {
        try {
            File tmp = new File(c.getFilesDir(), "inbox.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(new File(c.getFilesDir(), "inbox.json"));
        } catch (Exception ignored) { }
    }

    static synchronized void add(Context c, String app, String chat, String sender, String text, long when) {
        try {
            String id = hash(app + chat + sender + text);
            JSONArray a = load(c);
            for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).optString("id"))) return;
            JSONObject m = new JSONObject();
            m.put("id", id); m.put("app", app); m.put("chat", chat); m.put("sender", sender);
            m.put("text", text); m.put("when", when); m.put("got", System.currentTimeMillis()); m.put("read", false);
            a.put(m);
            if (a.length() > KEEP) { JSONArray b = new JSONArray(); for (int i = a.length() - KEEP; i < a.length(); i++) b.put(a.get(i)); a = b; }
            save(c, a);
        } catch (Exception ignored) { }
    }

    static synchronized void markRead(Context c, JSONObject req) {
        try {
            JSONArray a = load(c);
            boolean all = req.optBoolean("all");
            JSONArray ids = req.optJSONArray("ids");
            java.util.HashSet<String> set = new java.util.HashSet<>();
            if (ids != null) for (int i = 0; i < ids.length(); i++) set.add(ids.getString(i));
            for (int i = 0; i < a.length(); i++) { JSONObject m = a.getJSONObject(i); if (all || set.contains(m.optString("id"))) m.put("read", true); }
            save(c, a);
        } catch (Exception ignored) { }
    }

    static synchronized void clearRead(Context c) {
        try {
            JSONArray a = load(c), b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optBoolean("read")) b.put(a.get(i));
            save(c, b);
        } catch (Exception ignored) { }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }
}
