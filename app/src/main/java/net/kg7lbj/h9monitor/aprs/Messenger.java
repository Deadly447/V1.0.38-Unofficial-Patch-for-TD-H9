package net.kg7lbj.h9monitor.aprs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * APRS text messaging (APRS 1.0.1 chapter 14): sends messages with an ID,
 * retries until the other station acknowledges, and acknowledges messages
 * addressed to us. Pure Java, driven by {@link #tick(long)}; not thread-safe.
 */
public final class Messenger {
    public static final int MAX_TEXT = 67;
    public static final String TOCALL = "APZH9M";       // APZ = experimental software
    static final int MAX_SENDS = 5;
    /** Wait after send n for an ack before sending again (the last wait ends in FAILED). */
    static final long[] RETRY_AFTER_MS = {30_000, 60_000, 120_000, 240_000};
    static final long NOT_CONNECTED_RETRY_MS = 10_000;
    static final long DUP_WINDOW_MS = 30 * 60_000;

    public enum State { SENDING, DELIVERED, FAILED, REJECTED, RECEIVED }

    public static final class Msg {
        public final long time;
        public final String peer;
        public final boolean outgoing;
        /** What is shown: the plain text (decrypted if it was encrypted). */
        public String text;
        /** Exactly what goes on the air (same as text unless encrypted). */
        public final String wire;
        public final boolean encrypted;
        /** Encrypted, and we have no key (or the wrong one) to read it. */
        public boolean locked;
        public final String id;     // null for messages sent without an ID
        public State state;
        public int sends;           // transmissions so far (outgoing)
        public long nextSend;
        public long stateTime;

        Msg(long time, String peer, boolean outgoing, String text, String wire, String id, State state) {
            this.time = time;
            this.peer = peer;
            this.outgoing = outgoing;
            this.text = text;
            this.wire = wire;
            this.encrypted = Crypto.looksEncrypted(wire);
            this.id = id;
            this.state = state;
            this.stateTime = time;
        }
    }

    /** Hands a finished AX.25 frame to the radio. Returns false if it could not be sent now. */
    public interface Radio {
        boolean transmit(byte[] ax25);
    }

    /** Settings the messenger reads each time it sends. */
    public interface Config {
        String myCall();          // e.g. KG7LBJ-7, upper case; null = not set
        List<String> path();      // e.g. [WIDE1-1, NOGATE]
        /** Shared key for a conversation, or null. */
        default byte[] keyFor(String peer) { return null; }
        /** Whether messages to this station are sent encrypted. */
        default boolean encryptTo(String peer) { return false; }
    }

    public static final String LOCKED_TEXT = "🔒 Encrypted message. Set this conversation's passphrase to read it.";

    private final Config cfg;
    private final Radio radio;
    private final Map<String, List<Msg>> threads = new LinkedHashMap<>();
    private final Map<String, Long> seen = new HashMap<>();   // "FROM|id" -> time first heard
    private int nextId = 1;

    public Messenger(Config cfg, Radio radio) {
        this.cfg = cfg;
        this.radio = radio;
    }

    // ------------------------------------------------------------------ sending

    /** Cleans text the way APRS requires: printable ASCII, no | ~ {, at most 67 characters. */
    public static String cleanText(String t) {
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c < 0x20 || c > 0x7E || c == '|' || c == '~' || c == '{') continue;
            sb.append(c);
        }
        String s = sb.toString().trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** For encrypted messages: any text (UTF-8), just no control characters. */
    public static String cleanPlain(String t) {
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c < 0x20) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** Queues a message and sends it right away if the radio is connected. */
    public Msg send(String to, String text, long now) {
        String peer = to.trim().toUpperCase(Locale.US);
        if (!Ax25.validCall(peer)) throw new IllegalArgumentException("Not a valid callsign: " + to);
        if (cfg.myCall() == null) throw new IllegalStateException("Set your callsign first");
        boolean enc = cfg.encryptTo(peer);
        String body = enc ? cleanPlain(text) : cleanText(text);
        if (body.isEmpty()) throw new IllegalArgumentException("Message is empty");
        String wire = body;
        if (enc) {
            byte[] key = cfg.keyFor(peer);
            if (key == null) throw new IllegalStateException("Set a passphrase for " + peer + " first");
            wire = Crypto.encrypt(key, cfg.myCall(), peer, body);   // throws if too long
        }
        String id = Integer.toString(nextId);
        nextId = nextId >= 999 ? 1 : nextId + 1;
        Msg m = new Msg(now, peer, true, body, wire, id, State.SENDING);
        thread(peer).add(m);
        m.nextSend = now;
        tick(now);
        return m;
    }

    /** Sends any message that is due; call every few seconds. Returns true if anything changed. */
    public boolean tick(long now) {
        boolean changed = false;
        for (List<Msg> l : threads.values()) {
            for (Msg m : l) {
                if (!m.outgoing || m.state != State.SENDING || m.nextSend > now) continue;
                if (m.sends >= MAX_SENDS) {
                    m.state = State.FAILED;
                    m.stateTime = now;
                } else if (transmit(m.peer, m.wire + "{" + m.id)) {
                    m.sends++;
                    m.nextSend = now + RETRY_AFTER_MS[Math.min(m.sends - 1, RETRY_AFTER_MS.length - 1)];
                } else {
                    m.nextSend = now + NOT_CONNECTED_RETRY_MS;
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Gives up on a message, or sends a failed one again. */
    public void retry(Msg m, long now) {
        if (!m.outgoing) return;
        m.state = State.SENDING;
        m.sends = 0;
        m.nextSend = now;
        m.stateTime = now;
        tick(now);
    }

    public void cancel(Msg m, long now) {
        if (m.outgoing && m.state == State.SENDING) {
            m.state = State.FAILED;
            m.stateTime = now;
        }
    }

    private boolean transmit(String to, String text) {
        String me = cfg.myCall();
        if (me == null) return false;
        String info = ":" + pad9(to) + ":" + text;
        byte[] frame;
        try {
            frame = Ax25.encode(me, TOCALL, cfg.path(), info.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return radio.transmit(frame);
    }

    static String pad9(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 9) sb.append(' ');
        return sb.substring(0, 9);
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Looks at every decoded packet. Returns the new incoming message if this
     * packet is one addressed to us that we have not seen before, else null.
     */
    public Msg onPacket(AprsPacket p, long now) {
        String me = cfg.myCall();
        if (me == null || p.type != AprsPacket.Type.MESSAGE || p.msgTo == null) return null;
        if (!p.msgTo.trim().equalsIgnoreCase(me)) return null;
        String from = p.sender.toUpperCase(Locale.US);
        if (from.equalsIgnoreCase(me)) return null;          // our own packet, digipeated back
        String text = p.msgText == null ? "" : p.msgText;

        // ack / rej of something we sent ("ack12", also reply-ack form "ack12}")
        String lower = text.toLowerCase(Locale.US);
        if ((lower.startsWith("ack") || lower.startsWith("rej")) && p.msgId == null && text.length() <= 9) {
            String id = text.substring(3).trim();
            int brace = id.indexOf('}');
            if (brace >= 0) id = id.substring(0, brace);
            boolean ack = lower.startsWith("ack");
            for (Msg m : thread(from)) {
                if (m.outgoing && id.equals(m.id) && (m.state == State.SENDING || m.state == State.FAILED)) {
                    m.state = ack ? State.DELIVERED : State.REJECTED;
                    m.stateTime = now;
                }
            }
            return null;
        }

        String id = p.msgId;
        if (id != null) {
            int brace = id.indexOf('}');   // reply-ack: "{MM}AA" carries an ack for our AA
            if (brace >= 0) {
                String acked = id.substring(brace + 1);
                id = id.substring(0, brace);
                for (Msg m : thread(from)) {
                    if (m.outgoing && acked.equals(m.id) && m.state == State.SENDING) {
                        m.state = State.DELIVERED;
                        m.stateTime = now;
                    }
                }
            }
            // Always ack, even duplicates: the sender retries because it missed our ack.
            transmit(from, "ack" + id);
            String key = from + "|" + id;
            Long first = seen.get(key);
            if (first != null && now - first < DUP_WINDOW_MS) return null;
            seen.put(key, now);
        } else {
            // no ID: no ack possible; drop exact repeats within the window
            String key = from + "|=" + text;
            Long first = seen.get(key);
            if (first != null && now - first < DUP_WINDOW_MS) return null;
            seen.put(key, now);
        }
        Msg m = new Msg(now, from, false, text, text, id, State.RECEIVED);
        if (m.encrypted) unlock(m, me);
        thread(from).add(m);
        return m;
    }

    /** Tries to decrypt an encrypted message with the conversation's current key. */
    private void unlock(Msg m, String me) {
        if (!m.encrypted) return;
        String from = m.outgoing ? me : m.peer, to = m.outgoing ? m.peer : me;
        String plain = me == null ? null : Crypto.decrypt(cfg.keyFor(m.peer), from, to, m.wire);
        m.locked = plain == null;
        m.text = plain == null ? LOCKED_TEXT : plain;
    }

    /** Call after a conversation's passphrase changes. */
    public void rekey(String peer) {
        for (Msg m : messages(peer)) unlock(m, cfg.myCall());
    }

    // ------------------------------------------------------------------ data

    private List<Msg> thread(String peer) {
        return threads.computeIfAbsent(peer, k -> new ArrayList<>());
    }

    public List<Msg> messages(String peer) {
        List<Msg> l = threads.get(peer);
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }

    /** Conversation partners, most recent first. */
    public List<String> peers() {
        List<String> p = new ArrayList<>(threads.keySet());
        p.removeIf(k -> threads.get(k).isEmpty());
        p.sort((a, b) -> Long.compare(last(b), last(a)));
        return p;
    }

    private long last(String peer) {
        List<Msg> l = threads.get(peer);
        return l.isEmpty() ? 0 : l.get(l.size() - 1).time;
    }

    public void deleteThread(String peer) {
        threads.remove(peer);
    }

    public void clear() {
        threads.clear();
        seen.clear();
    }

    // ------------------------------------------------------------------ saving

    /** One line per message: time, direction, state, sends, id, peer, text (tab separated). */
    public List<String> save() {
        List<String> out = new ArrayList<>();
        out.add("#nextId\t" + nextId);
        for (List<Msg> l : threads.values()) {
            for (Msg m : l) {
                out.add(m.time + "\t" + (m.outgoing ? "out" : "in") + "\t" + m.state + "\t" + m.sends + "\t"
                        + (m.id == null ? "" : m.id) + "\t" + m.peer + "\t"
                        // encrypted messages are stored as sent, never decrypted, on the phone
                        + (m.encrypted ? m.wire : m.text).replace('\t', ' ') + "\t" + (m.encrypted ? "enc" : ""));
            }
        }
        return out;
    }

    public void load(List<String> lines, long now) {
        clear();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("#nextId")) { nextId = Integer.parseInt(f[1]); continue; }
                if (f.length < 7) continue;
                Msg m = new Msg(Long.parseLong(f[0]), f[5], f[1].equals("out"), f[6], f[6],
                        f[4].isEmpty() ? null : f[4], State.valueOf(f[2]));
                if (m.encrypted) unlock(m, cfg.myCall());
                m.sends = Integer.parseInt(f[3]);
                m.nextSend = now;               // resume any unfinished sends
                thread(m.peer).add(m);
                if (!m.outgoing && m.id != null) seen.put(m.peer + "|" + m.id, m.time);
            } catch (RuntimeException ignored) {
                // skip damaged line
            }
        }
    }
}
