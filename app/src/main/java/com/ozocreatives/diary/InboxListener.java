package com.ozocreatives.diary;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects WhatsApp, Instagram, Facebook and YouTube notifications for the diary's Inbox. */
public class InboxListener extends NotificationListenerService {
    static final Map<String, String> APPS = new HashMap<>();
    static {
        APPS.put("com.whatsapp.w4b", "Business"); APPS.put("com.whatsapp", "WhatsApp");
        APPS.put("com.instagram.android", "Instagram"); APPS.put("com.instagram.barcelona", "Threads");
        APPS.put("com.facebook.katana", "Facebook"); APPS.put("com.facebook.lite", "Facebook");
        APPS.put("com.facebook.orca", "Messenger"); APPS.put("com.facebook.pages.app", "Meta Business");
        APPS.put("com.google.android.youtube", "YouTube"); APPS.put("com.google.android.apps.youtube.creator", "YT Studio");
    }
    static final Pattern SUMMARY = Pattern.compile("^\\d+\\s+(new\\s+)?(messages?|notifications?)(\\s+from\\s+\\d+\\s+chats?)?$", Pattern.CASE_INSENSITIVE);
    static final Pattern COUNT = Pattern.compile("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*", Pattern.CASE_INSENSITIVE);

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String app = APPS.get(sbn.getPackageName());
        if (app == null) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        if (ex == null) return;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String conv = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        long when = sbn.getPostTime();
        // MessagingStyle: take only the newest message
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) msgs[msgs.length - 1];
            String text = str(last.getCharSequence("text"));
            String sender = str(last.getCharSequence("sender"));
            String chat = !conv.isEmpty() ? conv : title;
            chat = COUNT.matcher(chat).replaceAll("").trim();
            if (sender.isEmpty()) sender = chat;
            if (!text.isEmpty()) { InboxStore.add(this, app, chat, sender, text, last.getLong("time", when)); return; }
        }
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() || text.isEmpty() || SUMMARY.matcher(text).matches()) return;
        if (text.contains("Checking for new messages") || text.contains("Backup in progress")) return;
        title = COUNT.matcher(title).replaceAll("").trim();
        String chat = title, sender = title;
        boolean wa = app.equals("Business") || app.equals("WhatsApp");
        if (wa && title.contains(": ")) { int i = title.indexOf(": "); chat = title.substring(0, i); sender = title.substring(i + 2); }
        else if (wa && text.matches("^[^:]{1,40}: .*")) { int i = text.indexOf(": "); sender = text.substring(0, i); text = text.substring(i + 2); }
        InboxStore.add(this, app, chat, sender, text, when);
    }

    static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }
}
