package net.kg7lbj.h9monitor.aprs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An AX.25 UI frame as heard on the air. */
public final class Ax25 {
    public final String dest;
    public final String source;
    /** Digipeater path, with "*" after each digi that has repeated the frame. */
    public final List<String> path;
    public final int control;
    public final int pid;
    public final byte[] info;

    private Ax25(String dest, String source, List<String> path, int control, int pid, byte[] info) {
        this.dest = dest;
        this.source = source;
        this.path = Collections.unmodifiableList(path);
        this.control = control;
        this.pid = pid;
        this.info = info;
    }

    /** Parses a raw AX.25 frame (no FCS). Returns null if it is not a valid UI frame. */
    public static Ax25 parse(byte[] f) {
        if (f == null || f.length < 16) return null;
        List<String> addrs = new ArrayList<>();
        List<Boolean> repeated = new ArrayList<>();
        int p = 0;
        boolean last = false;
        while (!last) {
            if (p + 7 > f.length || addrs.size() >= 10) return null;
            StringBuilder cs = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                int c = (f[p + i] & 0xFF) >> 1;
                if (c == ' ') continue;
                if (c < 0x20 || c > 0x7E) return null;
                cs.append((char) c);
            }
            int s = f[p + 6] & 0xFF;
            int ssid = (s >> 1) & 0x0F;
            if (cs.length() == 0) return null;
            addrs.add(ssid == 0 ? cs.toString() : cs + "-" + ssid);
            repeated.add((s & 0x80) != 0);
            last = (s & 0x01) != 0;
            p += 7;
        }
        if (addrs.size() < 2 || p + 2 > f.length) return null;
        int control = f[p] & 0xFF;
        int pid = f[p + 1] & 0xFF;
        if (control != 0x03) return null;          // UI frames only
        byte[] info = new byte[f.length - p - 2];
        System.arraycopy(f, p + 2, info, 0, info.length);
        List<String> path = new ArrayList<>();
        for (int i = 2; i < addrs.size(); i++) path.add(addrs.get(i) + (repeated.get(i) ? "*" : ""));
        return new Ax25(addrs.get(0), addrs.get(1), path, control, pid, info);
    }

    /** Builds a frame from a TNC2 header (used for third-party packets). */
    public static Ax25 fromTnc2(String header, byte[] info) {
        int gt = header.indexOf('>');
        if (gt <= 0) return null;
        String src = header.substring(0, gt);
        String[] rest = header.substring(gt + 1).split(",");
        if (rest.length == 0 || rest[0].isEmpty()) return null;
        List<String> path = new ArrayList<>();
        for (int i = 1; i < rest.length; i++) path.add(rest[i]);
        return new Ax25(rest[0], src, path, 0x03, 0xF0, info);
    }

    /** Valid AX.25 station or alias: 1-6 letters/digits, optional -0..-15. */
    public static boolean validCall(String c) {
        return c != null && c.matches("[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?");
    }

    /** Builds a UI frame (control 0x03, PID 0xF0). Path entries are unrepeated aliases or calls. */
    public static byte[] encode(String source, String dest, List<String> path, byte[] info) {
        List<String> all = new ArrayList<>();
        all.add(dest);
        all.add(source);
        all.addAll(path);
        if (all.size() > 10) throw new IllegalArgumentException("path too long");
        byte[] out = new byte[all.size() * 7 + 2 + info.length];
        int p = 0;
        for (int i = 0; i < all.size(); i++) {
            String a = all.get(i).toUpperCase(java.util.Locale.US);
            if (!validCall(a)) throw new IllegalArgumentException("bad callsign " + a);
            int dash = a.indexOf('-');
            String call = dash < 0 ? a : a.substring(0, dash);
            int ssid = dash < 0 ? 0 : Integer.parseInt(a.substring(dash + 1));
            for (int k = 0; k < 6; k++) {
                char c = k < call.length() ? call.charAt(k) : ' ';
                out[p++] = (byte) (c << 1);
            }
            int s = 0x60 | (ssid << 1);
            if (i == 0) s |= 0x80;                 // command frame: C bit on destination
            if (i == all.size() - 1) s |= 0x01;    // end of address field
            out[p++] = (byte) s;
        }
        out[p++] = 0x03;
        out[p++] = (byte) 0xF0;
        System.arraycopy(info, 0, out, p, info.length);
        return out;
    }

    /** Info field as text (bytes kept 1:1, as APRS parsing needs). */
    public String infoLatin1() {
        return new String(info, StandardCharsets.ISO_8859_1);
    }

    /** The last digipeater that repeated this frame, or null if heard direct. */
    public String heardVia() {
        String via = null;
        boolean any = false;
        for (String d : path) {
            if (!d.endsWith("*")) continue;
            any = true;
            String name = d.substring(0, d.length() - 1);
            // WIDEn-N, RELAY, TRACE are path aliases, not station callsigns
            if (!name.matches("(WIDE|TRACE|RELAY)\\d*(-\\d+)?")) via = name;
        }
        if (via == null && any) return "a digipeater";
        return via;
    }

    public boolean heardDirect() {
        for (String d : path) if (d.endsWith("*")) return false;
        return true;
    }

    /** TNC2 monitor format: SRC>DEST,PATH:info */
    public String toTnc2() {
        StringBuilder sb = new StringBuilder(source).append('>').append(dest);
        for (String d : path) sb.append(',').append(d);
        sb.append(':');
        for (byte b : info) {
            int c = b & 0xFF;
            if (c >= 0x20 && c != 0x7F) sb.append((char) c);
            else sb.append(String.format("<0x%02x>", c));
        }
        return sb.toString();
    }
}
