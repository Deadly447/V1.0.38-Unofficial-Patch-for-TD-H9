package net.kg7lbj.h9monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kg7lbj.h9monitor.aprs.AprsPacket;
import net.kg7lbj.h9monitor.aprs.Ax25;
import net.kg7lbj.h9monitor.aprs.Crypto;
import net.kg7lbj.h9monitor.aprs.Messenger;
import net.kg7lbj.h9monitor.aprs.StationStore;

/**
 * App-wide state: heard stations, connection status and the saved radio.
 * Everything here runs on the main thread. Packets are kept in a private file
 * inside the app's own storage so the list survives a restart; nothing leaves
 * the phone.
 */
public final class Repo {
    public enum State { STOPPED, SCANNING, CONNECTING, CONNECTED, WAITING }

    public interface Listener {
        void onRepoChanged();
    }

    private static Repo instance;

    public static Repo get(Context c) {
        if (instance == null) instance = new Repo(c.getApplicationContext());
        return instance;
    }

    /** Sends a finished AX.25 frame to the radio; set by RadioService while connected. */
    public interface Transmitter {
        boolean transmit(byte[] ax25);
    }

    static final String MSG_CHANNEL = "messages";
    private static final String LOG_FILE = "packets.log";
    private static final String MSG_FILE = "messages.txt";
    private static final long TICK_MS = 5000;
    private static final int KEEP_FILE_LINES = StationStore.KEEP_LOG;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    public final StationStore store = new StationStore();
    private State state = State.STOPPED;
    private String stateText = "Not connected";
    private int linesInFile;
    private Transmitter transmitter;
    public final Messenger messenger;
    private final Set<String> unread = new HashSet<>();
    private String openThread;        // conversation on screen right now (no notification for it)

    private Repo(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        messenger = new Messenger(new Messenger.Config() {
            @Override public String myCall() { return Repo.this.myCall(); }
            @Override public List<String> path() { return Repo.this.txPath(); }
            @Override public byte[] keyFor(String peer) { return Repo.this.keyFor(peer); }
            @Override public boolean encryptTo(String peer) { return Repo.this.encryptTo(peer); }
        }, ax25 -> transmitter != null && transmitter.transmit(ax25));
        load();
        loadMessages();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(MSG_CHANNEL, "Messages",
                        NotificationManager.IMPORTANCE_HIGH));
            }
        }
        main.postDelayed(ticker, TICK_MS);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (messenger.tick(System.currentTimeMillis())) messagesChanged();
            main.postDelayed(this, TICK_MS);
        }
    };

    // ---- messaging settings ----
    /** Your callsign with SSID, e.g. KG7LBJ-7; null until set. */
    public String myCall() { return prefs.getString("mycall", null); }
    public String pathText() { return prefs.getString("path", "WIDE1-1"); }
    public boolean rfOnly() { return prefs.getBoolean("rfonly", true); }

    /** Saves the settings; returns an error to show, or null if they were saved. */
    public String saveSettings(String call, String path, boolean rfOnly) {
        String c = call.trim().toUpperCase(Locale.US);
        if (!c.isEmpty() && !Ax25.validCall(c)) return "Callsign should look like KG7LBJ-7";
        String p = path.trim().toUpperCase(Locale.US).replace(" ", "");
        for (String hop : p.isEmpty() ? new String[0] : p.split(",")) {
            if (!Ax25.validCall(hop)) return "Path should look like WIDE1-1 or WIDE1-1,WIDE2-1";
        }
        if (p.split(",").length > 7) return "Path is too long";
        prefs.edit().putString("mycall", c.isEmpty() ? null : c).putString("path", p)
                .putBoolean("rfonly", rfOnly).apply();
        changed();
        return null;
    }

    List<String> txPath() {
        List<String> l = new ArrayList<>();
        String p = pathText();
        if (!p.isEmpty()) l.addAll(Arrays.asList(p.split(",")));
        // iGates (Direwolf, APRSIS32 and others) do not pass packets with NOGATE to the internet
        if (rfOnly() && !l.contains("NOGATE") && !l.contains("RFONLY")) l.add("NOGATE");
        return l;
    }

    // ---- encryption (research use; see the warning in the app) ----
    private static String keyPref(String peer) { return "key_" + peer.toUpperCase(Locale.US); }

    public byte[] keyFor(String peer) {
        String h = prefs.getString(keyPref(peer), null);
        return h == null ? null : unhex(h);
    }

    public boolean encryptTo(String peer) {
        return keyFor(peer) != null && prefs.getBoolean("enc_" + peer.toUpperCase(Locale.US), false);
    }

    public String fingerprint(String peer) {
        byte[] k = keyFor(peer);
        return k == null ? null : Crypto.fingerprint(k);
    }

    /** Derives the key off the main thread (it takes about a second), then saves it. */
    public void setPassphrase(String peer, String passphrase, boolean encryptOutgoing, Runnable done) {
        final String p = peer.toUpperCase(Locale.US);
        io.execute(() -> {
            byte[] key = Crypto.deriveKey(passphrase);
            main.post(() -> {
                prefs.edit().putString(keyPref(p), hex(key)).putBoolean("enc_" + p, encryptOutgoing).apply();
                messenger.rekey(p);
                messagesChanged();
                if (done != null) done.run();
            });
        });
    }

    public void setEncryptOutgoing(String peer, boolean on) {
        prefs.edit().putBoolean("enc_" + peer.toUpperCase(Locale.US), on).apply();
        changed();
    }

    public void removeKey(String peer) {
        String p = peer.toUpperCase(Locale.US);
        prefs.edit().remove(keyPref(p)).remove("enc_" + p).apply();
        messenger.rekey(p);
        messagesChanged();
    }

    public void setTransmitter(Transmitter t) {
        transmitter = t;
        if (t != null && messenger.tick(System.currentTimeMillis())) messagesChanged();
    }

    public boolean canTransmit() { return transmitter != null; }

    /** Sends a message; returns an error to show, or null. */
    public String sendMessage(String to, String text) {
        try {
            messenger.send(to, text, System.currentTimeMillis());
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        messagesChanged();
        return null;
    }

    public boolean isUnread(String peer) { return unread.contains(peer); }

    public void setOpenThread(String peer) {
        openThread = peer;
        if (peer != null && unread.remove(peer)) changed();
        if (peer != null) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(peer, 2);
        }
    }

    public void messagesChanged() {
        saveMessages();
        changed();
    }

    private void notifyMessage(Messenger.Msg m) {
        if (m.peer.equals(openThread)) return;
        unread.add(m.peer);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_THREAD, m.peer)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, m.peer.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, MSG_CHANNEL) : new Notification.Builder(ctx);
        Notification n = b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(m.peer)
                .setContentText(m.text)
                .setStyle(new Notification.BigTextStyle().bigText(m.text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(m.peer, 2, n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    private void saveMessages() {
        final List<String> lines = messenger.save();
        io.execute(() -> {
            File tmp = new File(ctx.getFilesDir(), MSG_FILE + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (String l : lines) w.write(l + "\n");
            } catch (IOException e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(ctx.getFilesDir(), MSG_FILE));
        });
    }

    private void loadMessages() {
        File f = new File(ctx.getFilesDir(), MSG_FILE);
        if (!f.exists()) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        } catch (IOException e) {
            return;
        }
        messenger.load(lines, System.currentTimeMillis());
    }

    public void clearMessages() {
        messenger.clear();
        unread.clear();
        messagesChanged();
    }

    public void deleteThread(String peer) {
        messenger.deleteThread(peer);
        unread.remove(peer);
        messagesChanged();
    }

    // ---- saved radio ----
    public String radioAddress() { return prefs.getString("mac", null); }
    public String radioName() { return prefs.getString("name", null); }

    public void setRadio(String address, String name) {
        prefs.edit().putString("mac", address).putString("name", name).apply();
        changed();
    }


    // ---- connection state ----
    public State state() { return state; }
    public String stateText() { return stateText; }

    public void setState(State s, String text) {
        state = s;
        stateText = text;
        changed();
    }

    // ---- packets ----
    /** Records one AX.25 frame received from the radio. */
    public void addFrame(long now, byte[] ax25) {
        AprsPacket p = store.add(now, ax25);
        if (p == null) return;
        final String line = now + "\t" + hex(ax25) + "\n";
        io.execute(() -> append(line));
        Messenger.Msg in = messenger.onPacket(p, now);
        if (in != null) notifyMessage(in);
        if (p.type == AprsPacket.Type.MESSAGE) saveMessages();
        changed();
    }

    public void clear() {
        store.clear();
        io.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(ctx.getFilesDir(), LOG_FILE).delete();
            linesInFile = 0;
        });
        changed();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::changed);
            return;
        }
        for (Listener l : new ArrayList<>(listeners)) l.onRepoChanged();
    }

    // ---- private file ----
    private void load() {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        if (!f.exists()) return;
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > KEEP_FILE_LINES) lines.removeFirst();
            }
        } catch (IOException e) {
            return;
        }
        for (String l : lines) {
            int tab = l.indexOf('\t');
            if (tab <= 0) continue;
            try {
                store.add(Long.parseLong(l.substring(0, tab)), unhex(l.substring(tab + 1)));
            } catch (RuntimeException ignored) {
                // skip a damaged line
            }
        }
        linesInFile = lines.size();
    }

    private void append(String line) {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.US_ASCII)) {
            w.write(line);
        } catch (IOException ignored) {
            return;
        }
        if (++linesInFile > KEEP_FILE_LINES * 2) trim(f);
    }

    /** Keeps the file from growing without limit: rewrite it with the newest lines. */
    private void trim(File f) {
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > KEEP_FILE_LINES) lines.removeFirst();
            }
        } catch (IOException e) {
            return;
        }
        File tmp = new File(ctx.getFilesDir(), LOG_FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.US_ASCII)) {
            for (String l : lines) w.write(l + "\n");
        } catch (IOException e) {
            return;
        }
        if (tmp.renameTo(f)) linesInFile = lines.size();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
