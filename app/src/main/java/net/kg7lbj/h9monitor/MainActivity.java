package net.kg7lbj.h9monitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kg7lbj.h9monitor.aprs.AprsPacket;
import net.kg7lbj.h9monitor.aprs.Ax25;
import net.kg7lbj.h9monitor.aprs.AprsParser;
import net.kg7lbj.h9monitor.aprs.Crypto;
import net.kg7lbj.h9monitor.aprs.Format;
import net.kg7lbj.h9monitor.aprs.Kiss;
import net.kg7lbj.h9monitor.aprs.Messenger;
import net.kg7lbj.h9monitor.aprs.Station;
import net.kg7lbj.h9monitor.aprs.Symbols;

/** One screen: connection bar, then either the heard-stations list or the raw packet log. */
@SuppressLint("MissingPermission")   // checked in hasPermissions() before any Bluetooth call
public class MainActivity extends Activity implements Repo.Listener {
    public static final String EXTRA_THREAD = "thread";
    private static final int REQ_PERMS = 1;
    private static final int TAB_STATIONS = 0, TAB_MESSAGES = 1, TAB_LOG = 2;
    private static final int GREEN = 0xFF2E7D32, AMBER = 0xFFF9A825, GREY = 0xFF757575;

    private Repo repo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusDot, statusText, radioText, emptyText;
    private Button connectBtn, stationsTab, messagesTab, logTab, newMsgBtn;
    private ListView list;
    private int tab = TAB_STATIONS;
    private final PeerAdapter peerAdapter = new PeerAdapter();
    private Conversation conversation;   // open conversation dialog, if any
    private final StationAdapter stationAdapter = new StationAdapter();
    private final LogAdapter logAdapter = new LogAdapter();
    private Runnable afterPermissions;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshList();
            ui.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repo = Repo.get(this);
        setContentView(buildLayout());
        if (state != null) tab = state.getInt("tab");
        selectTab(tab);
        openThreadFrom(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openThreadFrom(intent);
    }

    private void openThreadFrom(Intent i) {
        String peer = i == null ? null : i.getStringExtra(EXTRA_THREAD);
        if (peer == null) return;
        i.removeExtra(EXTRA_THREAD);
        selectTab(TAB_MESSAGES);
        openConversation(peer);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        repo.addListener(this);
        onRepoChanged();
        ui.postDelayed(tick, 10000);
    }

    @Override
    protected void onPause() {
        repo.removeListener(this);
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onRepoChanged() {
        Repo.State s = repo.state();
        statusText.setText(repo.stateText());
        int c = s == Repo.State.CONNECTED ? GREEN : s == Repo.State.STOPPED ? GREY : AMBER;
        statusDot.setTextColor(c);
        connectBtn.setText(s == Repo.State.STOPPED ? "Connect" : "Disconnect");
        String name = repo.radioName(), mac = repo.radioAddress();
        radioText.setText(mac == null ? "No radio chosen" : (name != null ? name + "  " : "") + mac);
        stationsTab.setText("Stations (" + repo.store.size() + ")");
        int unread = 0;
        for (String p : repo.messenger.peers()) if (repo.isUnread(p)) unread++;
        messagesTab.setText(unread > 0 ? "Messages (" + unread + " new)" : "Messages");
        refreshList();
        if (conversation != null) conversation.refresh();
    }

    private void refreshList() {
        stationAdapter.setItems(repo.store.stations());
        logAdapter.setItems(repo.store.log());
        peerAdapter.setItems(repo.messenger.peers());
        boolean empty = tab == TAB_LOG ? logAdapter.getCount() == 0
                : tab == TAB_MESSAGES ? peerAdapter.getCount() == 0 : stationAdapter.getCount() == 0;
        emptyText.setText(tab == TAB_MESSAGES
                ? (repo.myCall() == null
                    ? "No messages yet.\n\nSet your callsign under Settings (top right) to send and receive messages."
                    : "No messages yet.\n\nMessages to " + repo.myCall() + " show up here. Tap New message to send one.")
                : "Nothing heard yet.\n\nChoose your radio, press Connect, and packets the radio "
                    + "decodes will show up here.");
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        newMsgBtn.setVisibility(tab == TAB_MESSAGES ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(8));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(18);
        statusDot.setPadding(0, 0, dp(8), 0);
        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(statusDot);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(statusRow);

        radioText = new TextView(this);
        radioText.setTextSize(13);
        radioText.setPadding(dp(26), 0, 0, dp(6));
        bar.addView(radioText);

        LinearLayout buttons = new LinearLayout(this);
        connectBtn = button("Connect", v -> toggleConnection());
        buttons.addView(connectBtn, weight());
        buttons.addView(button("Choose radio", v -> withPermissions(this::chooseRadio)), weight());
        buttons.addView(button("Clear", v -> confirmClear()), weight());
        bar.addView(buttons);
        root.addView(bar);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(8), 0, dp(8), 0);
        stationsTab = button("Stations", v -> selectTab(TAB_STATIONS));
        messagesTab = button("Messages", v -> selectTab(TAB_MESSAGES));
        logTab = button("Log", v -> selectTab(TAB_LOG));
        tabs.addView(stationsTab, weight());
        tabs.addView(messagesTab, weight());
        tabs.addView(logTab, weight());
        root.addView(tabs);

        newMsgBtn = button("New message", v -> newMessage(null));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.setMargins(dp(8), 0, dp(8), 0);
        root.addView(newMsgBtn, nl);

        emptyText = new TextView(this);
        emptyText.setText("Nothing heard yet.\n\nChoose your radio, press Connect, and packets the radio "
                + "decodes will show up here.");
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(32), dp(48), dp(32), dp(16));
        root.addView(emptyText);

        list = new ListView(this);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (tab == TAB_LOG) showPacket(logAdapter.getItem(pos));
            else if (tab == TAB_MESSAGES) openConversation(peerAdapter.getItem(pos));
            else showStation(stationAdapter.getItem(pos));
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (tab != TAB_MESSAGES) return false;
            String peer = peerAdapter.getItem(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Delete the conversation with " + peer + "?")
                    .setPositiveButton("Delete", (d, w) -> repo.deleteThread(peer))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void selectTab(int t) {
        tab = t;
        Button[] b = {stationsTab, messagesTab, logTab};
        for (int i = 0; i < b.length; i++) {
            b[i].setTypeface(i == t ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b[i].setEnabled(i != t);
        }
        list.setAdapter(t == TAB_LOG ? logAdapter : t == TAB_MESSAGES ? peerAdapter : stationAdapter);
        refreshList();
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Settings");
        menu.add(0, 2, 1, "Clear messages");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { showSettings(); return true; }
        if (item.getItemId() == 2) {
            new AlertDialog.Builder(this)
                    .setMessage("Delete all messages?")
                    .setPositiveButton("Delete", (d, w) -> repo.clearMessages())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView l1 = label("Your callsign and SSID (used when you send messages)");
        EditText call = edit(repo.myCall() == null ? "" : repo.myCall(), "KG7LBJ-7");
        call.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView l2 = label("Path");
        EditText path = edit(repo.pathText(), "WIDE1-1");
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CheckBox rf = new CheckBox(this);
        rf.setText("Keep my messages off the internet (adds NOGATE so iGates do not forward them)");
        rf.setChecked(repo.rfOnly());
        TextView note = label("Messages go out over the air in plain text, like all APRS. Anyone listening "
                + "can read them.");
        box.addView(l1); box.addView(call); box.addView(l2); box.addView(path); box.addView(rf); box.addView(note);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String err = repo.saveSettings(call.getText().toString(), path.getText().toString(), rf.isChecked());
            if (err != null) toast(err);
            else d.dismiss();
        }));
        d.show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText edit(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    // ---------------------------------------------------------------- messages

    private boolean readyToSend() {
        if (repo.myCall() == null) {
            toast("Set your callsign in Settings first");
            showSettings();
            return false;
        }
        return true;
    }

    private void newMessage(String to) {
        if (!readyToSend()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText who = edit(to == null ? "" : to, "To (callsign, e.g. N0CALL-7)");
        who.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText text = edit("", "Message (up to 67 characters)");
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Messenger.MAX_TEXT)});
        box.addView(who);
        box.addView(text);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("New message")
                .setView(box)
                .setPositiveButton("Send", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String peer = who.getText().toString().trim().toUpperCase(Locale.US);
            String err = repo.sendMessage(peer, text.getText().toString());
            if (err != null) { toast(err); return; }
            if (!repo.canTransmit()) toast("Not connected: it will be sent when the radio connects");
            d.dismiss();
            openConversation(peer);
        }));
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void openConversation(String peer) {
        if (conversation != null) conversation.dialog.dismiss();
        conversation = new Conversation(peer);
    }

    /** A conversation with one station: the messages, then a box to type a reply. */
    private final class Conversation {
        final String peer;
        final AlertDialog dialog;
        final LinearLayout lines = new LinearLayout(MainActivity.this);
        final ScrollView scroll = new ScrollView(MainActivity.this);
        Button lockBtn;
        EditText text;
        int shown = -1;

        Conversation(String peer) {
            this.peer = peer;
            lines.setOrientation(LinearLayout.VERTICAL);
            lines.setPadding(dp(16), dp(8), dp(16), dp(8));
            scroll.addView(lines);
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            lockBtn = button("", v -> encryptionDialog(peer));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), 0, dp(12), 0);
            box.addView(lockBtn, lp);
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setPadding(dp(12), 0, dp(12), dp(4));
            text = edit("", "Message " + peer);
            Button send = button("Send", v -> {
                if (!readyToSend()) return;
                String err = repo.sendMessage(peer, text.getText().toString());
                if (err != null) { toast(err); return; }
                if (!repo.canTransmit()) toast("Not connected: it will be sent when the radio connects");
                text.setText("");
            });
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(send);
            box.addView(row);
            box.setMinimumHeight(getResources().getDisplayMetrics().heightPixels / 2);
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(peer)
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .create();
            dialog.setOnDismissListener(d -> {
                repo.setOpenThread(null);
                if (conversation == this) conversation = null;
            });
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            repo.setOpenThread(peer);
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            refresh();
        }

        void refresh() {
            boolean enc = repo.encryptTo(peer);
            lockBtn.setText(enc ? "🔒 Encrypted (tap to change)"
                    : repo.keyFor(peer) != null ? "Encryption key set, sending in plain text" : "Encryption: off");
            text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(enc ? Crypto.MAX_PLAIN_BYTES : Messenger.MAX_TEXT)});
            text.setHint(enc ? "🔒 Encrypted message to " + peer : "Message " + peer);
            List<Messenger.Msg> msgs = repo.messenger.messages(peer);
            lines.removeAllViews();
            if (msgs.isEmpty()) lines.addView(label("No messages with " + peer + " yet."));
            for (Messenger.Msg m : msgs) {
                TextView t = new TextView(MainActivity.this);
                t.setTextSize(15);
                t.setPadding(m.outgoing ? dp(48) : 0, dp(6), m.outgoing ? 0 : dp(48), dp(6));
                t.setGravity(m.outgoing ? Gravity.END : Gravity.START);
                String head = (m.outgoing ? "You" : m.peer) + "  " + time(m.time);
                String tail = m.outgoing ? "\n" + stateText(m) : "";
                t.setText(head + (m.encrypted && !m.locked ? "  🔒" : "") + "\n" + m.text + tail);
                if (m.outgoing) t.setOnClickListener(v -> messageOptions(m));
                lines.addView(t);
            }
            if (msgs.size() != shown) {
                shown = msgs.size();
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void encryptionDialog(String peer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView warn = label("⚠ For research and bench testing. Sending encrypted messages on amateur "
                + "frequencies is prohibited in the US (FCC §97.113(a)(4)) and most other countries. "
                + "Use a dummy load or a license that allows it. Your callsign is still sent in plain text.");
        warn.setTextColor(0xFFE65100);
        String fp = repo.fingerprint(peer);
        TextView state = label(fp == null ? "No passphrase set for " + peer + "."
                : "Key fingerprint " + fp + ". Both phones should show the same code.");
        EditText pass = edit("", fp == null ? "Shared passphrase" : "New passphrase (leave empty to keep)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox on = new CheckBox(this);
        on.setText("Encrypt messages I send to " + peer);
        on.setChecked(repo.encryptTo(peer));
        TextView note = label("Messages from " + peer + " are decrypted with this passphrase whenever it is set. "
                + "Encrypted messages are stored on the phone as sent, not decrypted.");
        box.addView(warn); box.addView(state); box.addView(pass); box.addView(on); box.addView(note);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Encryption with " + peer)
                .setView(sv)
                .setPositiveButton("Save", null)
                .setNeutralButton("Test on phone", null)
                .setNegativeButton(fp == null ? "Cancel" : "Remove key", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String p = pass.getText().toString();
                if (p.isEmpty()) {
                    if (repo.keyFor(peer) == null) {
                        if (on.isChecked()) { toast("Enter a passphrase"); return; }
                    } else repo.setEncryptOutgoing(peer, on.isChecked());
                    d.dismiss();
                    return;
                }
                if (p.length() < 8) { toast("Use at least 8 characters"); return; }
                toast("Making the key…");
                repo.setPassphrase(peer, p, on.isChecked(), () -> toast("Key fingerprint " + repo.fingerprint(peer)));
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (repo.keyFor(peer) != null) repo.removeKey(peer);
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> testOnPhone(peer, pass.getText().toString()));
        });
        d.show();
    }

    /**
     * Runs a message through every step the radio link uses (encrypt, APRS
     * message, AX.25, KISS, then back) without sending anything.
     */
    private void testOnPhone(String peer, String passphrase) {
        byte[] key = passphrase.isEmpty() ? repo.keyFor(peer) : null;
        String me = repo.myCall() != null ? repo.myCall() : "N0CALL";
        if (key == null && passphrase.isEmpty()) { toast("Enter a passphrase to test"); return; }
        new Thread(() -> {
            byte[] k = key != null ? key : Crypto.deriveKey(passphrase);
            String plain = "Test " + time(System.currentTimeMillis()) + " ✓";
            StringBuilder r = new StringBuilder();
            try {
                String wire = Crypto.encrypt(k, me, peer, plain);
                String info = ":" + (peer + "         ").substring(0, 9) + ":" + wire + "{1";
                byte[] frame = Ax25.encode(me, Messenger.TOCALL, repo.txPath(),
                        info.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                byte[] back = new Kiss().feed(Kiss.encode(frame)).get(0);
                AprsPacket p = AprsParser.parse(Ax25.parse(back));
                String out = Crypto.decrypt(k, p.sender, p.msgTo, p.msgText);
                r.append(plain.equals(out) ? "✓ Round trip OK\n\n" : "✗ Round trip FAILED\n\n")
                        .append("Plain: ").append(plain).append("\n\n")
                        .append("On the air (").append(info.length()).append(" of 256 bytes):\n")
                        .append(p.raw).append("\n\n")
                        .append("Key fingerprint ").append(Crypto.fingerprint(k));
            } catch (RuntimeException e) {
                r.append("✗ ").append(e);
            }
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Phone-only test (nothing sent)")
                    .setView(textBlock(r.toString()))
                    .setPositiveButton("Close", null)
                    .show());
        }).start();
    }

    private static String stateText(Messenger.Msg m) {
        switch (m.state) {
            case DELIVERED: return "✓ Delivered";
            case REJECTED: return "✗ Refused by the other station";
            case FAILED: return "✗ No reply, tap to send again";
            default: return m.sends == 0 ? "Waiting for the radio…" : "Sent " + m.sends + "×, waiting for a reply";
        }
    }

    private void messageOptions(Messenger.Msg m) {
        if (m.state == Messenger.State.DELIVERED) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(m.text);
        if (m.state == Messenger.State.SENDING) {
            b.setPositiveButton("Stop trying", (d, w) -> {
                repo.messenger.cancel(m, System.currentTimeMillis());
                repo.messagesChanged();
            });
        } else {
            b.setPositiveButton("Send again", (d, w) -> {
                repo.messenger.retry(m, System.currentTimeMillis());
                repo.messagesChanged();
            });
        }
        b.setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- actions

    private void toggleConnection() {
        if (repo.state() != Repo.State.STOPPED) {
            RadioService.stop(this);
            return;
        }
        if (repo.radioAddress() == null) {
            withPermissions(this::chooseRadio);
            return;
        }
        withPermissions(() -> {
            if (!bluetoothOn()) return;
            RadioService.start(this);
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Clear all heard stations and the packet log?")
                .setPositiveButton("Clear", (d, w) -> repo.clear())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean bluetoothOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) {
            toast("This phone has no Bluetooth");
            return false;
        }
        if (!a.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (RuntimeException e) {
                toast("Turn Bluetooth on first");
            }
            return false;
        }
        return true;
    }

    /** Scans for nearby Bluetooth LE devices and lets the user pick the radio. */
    private void chooseRadio() {
        if (!bluetoothOn()) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothLeScanner sc = bm.getAdapter().getBluetoothLeScanner();
        if (sc == null) { toast("Bluetooth scanning is not available"); return; }

        final Map<String, String> found = new LinkedHashMap<>();   // address -> name
        final List<String> rows = new ArrayList<>();
        final List<String> addrs = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                if (name == null) return;             // the radio always advertises its name
                final String n = name;
                ui.post(() -> {
                    if (found.containsKey(d.getAddress())) return;
                    found.put(d.getAddress(), n);
                    // radios first
                    int at = n.toUpperCase(Locale.US).contains("TD-H9") ? 0 : rows.size();
                    rows.add(at, n + "\n" + d.getAddress());
                    addrs.add(at, d.getAddress());
                    ad.notifyDataSetChanged();
                });
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Choose your radio (the one ending in \"(BLE)\")")
                .setAdapter(ad, (d, which) -> {
                    String a = addrs.get(which);
                    repo.setRadio(a, found.get(a));
                    toast("Saved. Press Connect.");
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnDismissListener(d -> {
            try { sc.stopScan(cb); } catch (RuntimeException ignored) { }
        });
        if (repo.state() != Repo.State.STOPPED) RadioService.stop(this);
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        dlg.show();
        ui.postDelayed(() -> {
            try { sc.stopScan(cb); } catch (RuntimeException ignored) { }
            if (dlg.isShowing() && rows.isEmpty()) dlg.setTitle("Nothing found. Is the radio on?");
        }, 15000);
    }

    // ---------------------------------------------------------------- permissions

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);   // Android 11 and older need it to scan
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        return missing.toArray(new String[0]);
    }

    private void withPermissions(Runnable r) {
        String[] missing = neededPermissions();
        if (missing.length == 0) { r.run(); return; }
        afterPermissions = r;
        requestPermissions(missing, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        boolean bt = true;
        for (int i = 0; i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED && !Manifest.permission.POST_NOTIFICATIONS.equals(perms[i])) {
                bt = false;
            }
        }
        Runnable r = afterPermissions;
        afterPermissions = null;
        if (bt && r != null) r.run();
        else if (!bt) toast("Bluetooth permission is needed to talk to the radio");
    }

    // ---------------------------------------------------------------- details

    private void showStation(Station s) {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        AprsPacket pos = s.lastPosition;
        if (pos != null) sb.append(Symbols.name(pos.symTable, pos.symCode)).append("\n");
        if (s.object && s.last != null) sb.append("Object sent by ").append(s.last.sender).append("\n");
        sb.append("Last heard ").append(Format.ago(now - s.lastHeard))
                .append(" (").append(time(s.lastHeard)).append(")\n");
        sb.append(s.lastVia == null ? "Heard direct" : "Heard via " + s.lastVia).append("\n");
        sb.append(s.count).append(s.count == 1 ? " packet" : " packets").append(" since ")
                .append(time(s.firstHeard)).append("\n\n");
        if (pos != null) {
            sb.append(Format.dm(pos.lat, pos.lon)).append("\n").append(Format.latLon(pos.lat, pos.lon));
            if (pos.ambiguity > 0) sb.append("  (approximate)");
            sb.append("\nPosition from ").append(Format.ago(now - s.lastPositionTime)).append("\n");
            String sc = Format.speedCourse(pos.speedKnots, pos.courseDeg);
            if (!sc.isEmpty()) sb.append(sc).append("\n");
            if (!Double.isNaN(pos.altitudeM)) sb.append("Altitude ").append(Format.altitude(pos.altitudeM)).append("\n");
            if (pos.miceStatus != null) sb.append("Status: ").append(pos.miceStatus).append("\n");
            if (pos.comment != null) sb.append(pos.comment).append("\n");
            sb.append("\n");
        }
        if (s.wx != null && !s.wx.summary().isEmpty()) sb.append("Weather: ").append(s.wx.summary()).append("\n\n");
        if (s.status != null) sb.append("Status text: ").append(s.status).append("\n\n");
        sb.append("Recent packets:\n");
        for (Station.Heard h : s.history) sb.append(time(h.time)).append("  ").append(h.packet.raw).append("\n");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(Symbols.icon(s.symTable(), s.symCode()) + "  " + s.name)
                .setView(textBlock(sb.toString()))
                .setNegativeButton("Close", null);
        if (pos != null) {
            final double la = pos.lat, lo = pos.lon;
            final String label = s.name;
            b.setPositiveButton("Open in map app", (d, w) -> openMap(la, lo, label));
        }
        if (!s.object) b.setNeutralButton("Message", (d, w) -> openConversation(s.name));
        b.show();
    }

    private void showPacket(Station.Heard h) {
        AprsPacket p = h.packet;
        String body = time(h.time) + "\n\n" + p.raw + "\n\n" + p.type.name().replace('_', '-')
                + (p.heardVia == null ? ", heard direct" : ", via " + p.heardVia)
                + (p.thirdParty ? ", relayed from the internet by " + p.raw.substring(0, p.raw.indexOf('>')) : "");
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(textBlock(body))
                .setPositiveButton("Station", (d, w) -> {
                    Station s = repo.store.get(p.name);
                    if (s != null) showStation(s);
                })
                .setNeutralButton("Copy", (d, w) -> copy(p.raw))
                .setNegativeButton("Close", null)
                .show();
    }

    private View textBlock(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setTextSize(14);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        return sv;
    }

    /** Hands the position to whatever map app is installed (for example OsmAnd with offline maps). */
    private void openMap(double lat, double lon, String label) {
        String q = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        Uri u = Uri.parse("geo:" + q + "?q=" + q + "(" + Uri.encode(label) + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast("No map app installed");
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("APRS", s));
        if (Build.VERSION.SDK_INT < 33) toast("Copied");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String time(long t) {
        return new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date(t));
    }

    // ---------------------------------------------------------------- list adapters

    private TwoLine twoLine(View convert) {
        if (convert != null) return (TwoLine) convert.getTag();
        return new TwoLine();
    }

    private final class TwoLine {
        final LinearLayout root = new LinearLayout(MainActivity.this);
        final TextView top = new TextView(MainActivity.this);
        final TextView bottom = new TextView(MainActivity.this);

        TwoLine() {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(16), dp(10), dp(16), dp(10));
            top.setTextSize(16);
            top.setSingleLine(true);
            top.setEllipsize(TextUtils.TruncateAt.END);
            bottom.setTextSize(13);
            bottom.setMaxLines(2);
            bottom.setEllipsize(TextUtils.TruncateAt.END);
            bottom.setTextColor(Color.GRAY);
            root.addView(top);
            root.addView(bottom);
            root.setTag(this);
        }
    }

    private final class StationAdapter extends BaseAdapter {
        private List<Station> items = new ArrayList<>();

        void setItems(List<Station> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public Station getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TwoLine v = twoLine(convert);
            Station s = items.get(i);
            long now = System.currentTimeMillis();
            String via = s.lastVia == null ? "direct" : "via " + s.lastVia;
            v.top.setText(Symbols.icon(s.symTable(), s.symCode()) + "  " + s.name + "   ·  "
                    + Format.ago(now - s.lastHeard) + "  ·  " + via);
            String sum = s.summary();
            v.bottom.setText(sum.isEmpty() ? s.count + " packets" : sum);
            v.top.setTypeface(Typeface.DEFAULT);
            v.bottom.setTypeface(Typeface.DEFAULT);
            return v.root;
        }
    }

    private final class PeerAdapter extends BaseAdapter {
        private List<String> items = new ArrayList<>();

        void setItems(List<String> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public String getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TwoLine v = twoLine(convert);
            String peer = items.get(i);
            List<Messenger.Msg> msgs = repo.messenger.messages(peer);
            Messenger.Msg last = msgs.get(msgs.size() - 1);
            v.top.setText((repo.isUnread(peer) ? "● " : "") + peer + "   ·  "
                    + Format.ago(System.currentTimeMillis() - last.time));
            v.top.setTypeface(repo.isUnread(peer) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            v.bottom.setText((last.outgoing ? "You: " : "") + (last.encrypted ? "🔒 " : "") + last.text);
            v.bottom.setTypeface(Typeface.DEFAULT);
            return v.root;
        }
    }

    private final class LogAdapter extends BaseAdapter {
        private List<Station.Heard> items = new ArrayList<>();

        void setItems(List<Station.Heard> l) { items = l; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public Station.Heard getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TwoLine v = twoLine(convert);
            Station.Heard h = items.get(i);
            v.top.setText(time(h.time) + "  " + h.packet.name);
            v.bottom.setText(h.packet.raw);
            v.top.setTypeface(Typeface.DEFAULT);
            v.bottom.setTypeface(Typeface.MONOSPACE);
            return v.root;
        }
    }
}
