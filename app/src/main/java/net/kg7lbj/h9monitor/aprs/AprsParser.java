package net.kg7lbj.h9monitor.aprs;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APRS 1.0.1 decoder for what shows up on RF: plain and compressed positions,
 * Mic-E, objects, items, status, messages, weather and telemetry. Anything else
 * is kept as OTHER with its raw text.
 */
public final class AprsParser {
    private AprsParser() {}

    private static final Pattern ALT = Pattern.compile("/A=(-?\\d{5,6})");
    private static final Pattern CSE_SPD = Pattern.compile("^(\\d{3})/(\\d{3})");
    private static final String[] MICE_STD = {"Emergency", "Priority", "Special", "Committed",
            "Returning", "In Service", "En Route", "Off Duty"};
    private static final String[] MICE_CUSTOM = {"Emergency", "Custom-6", "Custom-5", "Custom-4",
            "Custom-3", "Custom-2", "Custom-1", "Custom-0"};

    public static AprsPacket parse(Ax25 f) {
        AprsPacket p = new AprsPacket();
        p.sender = f.source;
        p.name = f.source;
        p.dest = f.dest;
        p.path = String.join(",", f.path);
        p.heardVia = f.heardVia();
        p.raw = f.toTnc2();
        String s = f.infoLatin1();
        if (s.isEmpty()) return p;
        try {
            decode(p, f, s, 0);
        } catch (RuntimeException e) {
            // malformed packet: keep what we have, show it as raw
            p.type = AprsPacket.Type.OTHER;
        }
        if (p.comment != null) {
            p.comment = Format.text(p.comment).trim();
            if (p.comment.isEmpty()) p.comment = null;
        }
        return p;
    }

    private static void decode(AprsPacket p, Ax25 f, String s, int depth) {
        char t = s.charAt(0);
        switch (t) {
            case '!': case '=':
                p.type = AprsPacket.Type.POSITION;
                position(p, s, 1);
                break;
            case '/': case '@':
                p.type = AprsPacket.Type.POSITION;
                if (s.length() >= 8) position(p, s, 8);
                break;
            case '`': case '\'': case 0x1C: case 0x1D:
                p.type = AprsPacket.Type.MIC_E;
                micE(p, f.dest, s);
                break;
            case ';':
                p.type = AprsPacket.Type.OBJECT;
                if (s.length() >= 18) {
                    p.name = s.substring(1, 10).trim();
                    p.killed = s.charAt(10) == '_';
                    position(p, s, 18);
                }
                break;
            case ')': {
                p.type = AprsPacket.Type.ITEM;
                int i = 1;
                while (i < s.length() && i <= 10 && s.charAt(i) != '!' && s.charAt(i) != '_') i++;
                if (i < s.length()) {
                    p.name = s.substring(1, i).trim();
                    p.killed = s.charAt(i) == '_';
                    position(p, s, i + 1);
                }
                break;
            }
            case '>':
                p.type = AprsPacket.Type.STATUS;
                String st = s.substring(1);
                if (st.matches("^\\d{6}z.*")) st = st.substring(7);
                p.status = Format.text(st).trim();
                break;
            case ':':
                if (s.length() >= 11 && s.charAt(10) == ':') {
                    String to = s.substring(1, 10).trim();
                    String body = s.substring(11);
                    if (body.startsWith("PARM.") || body.startsWith("UNIT.") || body.startsWith("EQNS.")
                            || body.startsWith("BITS.")) {
                        p.type = AprsPacket.Type.TELEMETRY;
                        p.telemetry = body;
                    } else {
                        p.type = AprsPacket.Type.MESSAGE;
                        p.msgTo = to;
                        int b = body.lastIndexOf('{');
                        if (b >= 0 && body.length() - b <= 6) {
                            p.msgId = body.substring(b + 1);
                            body = body.substring(0, b);
                        }
                        p.msgText = Format.text(body);
                    }
                }
                break;
            case '_':
                p.type = AprsPacket.Type.WEATHER;
                p.wx = new AprsPacket.Weather();
                if (s.length() > 9) weather(p.wx, s.substring(9), true);
                break;
            case 'T':
                if (s.startsWith("T#")) {
                    p.type = AprsPacket.Type.TELEMETRY;
                    p.telemetry = s.substring(2);
                }
                break;
            case '}':
                if (depth < 2) {
                    int c = s.indexOf(':');
                    if (c > 1) {
                        Ax25 inner = Ax25.fromTnc2(s.substring(1, c),
                                s.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1));
                        if (inner != null && inner.info.length > 0) {
                            p.thirdParty = true;
                            p.sender = inner.source;
                            p.name = inner.source;
                            p.dest = inner.dest;
                            decode(p, inner, inner.infoLatin1(), depth + 1);
                        }
                    }
                }
                break;
            default:
                // A '!' position may appear up to 40 characters into the field (X1J beacons)
                int bang = s.indexOf('!');
                if (bang > 0 && bang < 40 && s.length() > bang + 13) {
                    p.type = AprsPacket.Type.POSITION;
                    position(p, s, bang + 1);
                    if (!p.hasPosition()) p.type = AprsPacket.Type.OTHER;
                }
        }
    }

    /** Position at s[i]: plain (19 chars) or compressed (13 chars), then extensions and comment. */
    private static void position(AprsPacket p, String s, int i) {
        if (s.length() < i + 13) return;
        char c0 = s.charAt(i);
        String rest;
        if (Character.isDigit(c0) || c0 == ' ') {
            if (s.length() < i + 19) return;
            String la = s.substring(i, i + 8);
            p.symTable = s.charAt(i + 8);
            String lo = s.substring(i + 9, i + 18);
            p.symCode = s.charAt(i + 18);
            double[] lat = plainCoord(la, 2);
            double[] lon = plainCoord(lo, 3);
            if (lat == null || lon == null) return;
            p.lat = lat[0];
            p.lon = lon[0];
            p.ambiguity = (int) lat[1];
            rest = s.substring(i + 19);
            Matcher m = CSE_SPD.matcher(rest);
            if (m.find() && p.symCode != '_') {
                int cse = Integer.parseInt(m.group(1));
                int spd = Integer.parseInt(m.group(2));
                if (cse > 0 && cse <= 360) p.courseDeg = cse % 360;
                if (cse > 0 || spd > 0) p.speedKnots = spd;
                rest = rest.substring(7);
            }
        } else {
            p.symTable = c0;
            String y = s.substring(i + 1, i + 5), x = s.substring(i + 5, i + 9);
            p.symCode = s.charAt(i + 9);
            p.lat = 90 - base91(y) / 380926.0;
            p.lon = -180 + base91(x) / 190463.0;
            char c = s.charAt(i + 10), sp = s.charAt(i + 11), ct = s.charAt(i + 12);
            if (c != ' ') {
                int tt = ct - 33;
                if ((tt & 0x18) == 0x10) {
                    p.altitudeM = Math.pow(1.002, (c - 33) * 91 + (sp - 33)) * 0.3048;
                } else if (c >= '!' && c <= 'z') {
                    p.courseDeg = ((c - 33) * 4) % 360;
                    p.speedKnots = Math.pow(1.08, sp - 33) - 1;
                }
            }
            if (p.symTable >= 'a' && p.symTable <= 'j') p.symTable = (char) ('0' + p.symTable - 'a');
            rest = s.substring(i + 13);
            if (Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) { p.lat = p.lon = Double.NaN; }
        }
        if (p.symCode == '_') {
            p.type = p.type == AprsPacket.Type.POSITION ? AprsPacket.Type.WEATHER : p.type;
            p.wx = new AprsPacket.Weather();
            rest = weather(p.wx, rest, false);
        }
        p.comment = altitudeFrom(p, rest);
    }

    /** Returns {value, ambiguity}. Spaces (position ambiguity) are read as the middle of the range. */
    private static double[] plainCoord(String v, int degDigits) {
        char hemi = Character.toUpperCase(v.charAt(v.length() - 1));
        String num = v.substring(0, v.length() - 1);
        int blanks = 0;
        StringBuilder sb = new StringBuilder();
        for (char ch : num.toCharArray()) {
            if (ch == ' ') { blanks++; sb.append('0'); } else sb.append(ch);
        }
        String n = sb.toString();
        if (n.length() != degDigits + 5 || n.charAt(degDigits + 2) != '.') return null;
        int deg;
        double min;
        try {
            deg = Integer.parseInt(n.substring(0, degDigits));
            min = Double.parseDouble(n.substring(degDigits));
        } catch (NumberFormatException e) {
            return null;
        }
        if (min >= 60) return null;
        // centre of the blanked range
        double[] half = {0, 0.005, 0.05, 0.5, 5};
        if (blanks > 0 && blanks <= 4) min += half[blanks];
        double val = deg + min / 60.0;
        if (hemi == 'S' || hemi == 'W') val = -val;
        else if (hemi != 'N' && hemi != 'E') return null;
        if ((degDigits == 2 && Math.abs(val) > 90) || Math.abs(val) > 180) return null;
        return new double[]{val, blanks};
    }

    private static long base91(String s) {
        long v = 0;
        for (int k = 0; k < s.length(); k++) {
            int c = s.charAt(k) - 33;
            if (c < 0 || c > 90) throw new IllegalArgumentException("bad base91");
            v = v * 91 + c;
        }
        return v;
    }

    private static String altitudeFrom(AprsPacket p, String comment) {
        Matcher m = ALT.matcher(comment);
        if (m.find()) {
            p.altitudeM = Integer.parseInt(m.group(1)) * 0.3048;
            comment = comment.substring(0, m.start()) + comment.substring(m.end());
        }
        return comment;
    }

    /**
     * Reads weather fields. For a position report the string starts with
     * "ddd/sss" (wind direction/speed). Returns what is left (the comment).
     */
    private static String weather(AprsPacket.Weather w, String s, boolean positionless) {
        int i = 0;
        if (!positionless && s.length() >= 7 && s.charAt(3) == '/') {
            w.windDir = num(s.substring(0, 3));
            w.windMph = num(s.substring(4, 7));
            i = 7;
        }
        while (i < s.length()) {
            char k = s.charAt(i);
            int len;
            switch (k) {
                case 'c': case 's': case 'g': case 't': case 'r': case 'p': case 'P': case 'L': case 'l':
                    len = 3; break;
                case 'h': len = 2; break;
                case 'b': len = 5; break;
                default: return s.substring(i);
            }
            if (i + 1 + len > s.length()) return s.substring(i);
            String v = s.substring(i + 1, i + 1 + len);
            if (!v.matches("[-\\d. ]+")) return s.substring(i);
            double d = num(v);
            switch (k) {
                case 'c': w.windDir = d; break;
                case 's': w.windMph = d; break;
                case 'g': w.gustMph = d; break;
                case 't': w.tempF = d; break;
                case 'r': w.rain1hIn = d / 100; break;
                case 'p': w.rain24hIn = d / 100; break;
                case 'P': w.rainMidnightIn = d / 100; break;
                case 'h': w.humidity = d == 0 ? 100 : d; break;
                case 'b': w.pressureMb = d / 10; break;
                default: break;
            }
            i += 1 + len;
        }
        return "";
    }

    private static double num(String v) {
        String t = v.trim();
        if (t.isEmpty() || t.matches("\\.+") || t.matches("-+")) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Mic-E: latitude and message bits ride in the destination callsign. */
    private static void micE(AprsPacket p, String dest, String s) {
        String d = dest;
        int dash = d.indexOf('-');
        if (dash >= 0) d = d.substring(0, dash);
        if (d.length() != 6 || s.length() < 9) return;
        int[] digit = new int[6];
        int msgBits = 0;
        boolean custom = false;
        for (int k = 0; k < 6; k++) {
            char c = d.charAt(k);
            int v;
            if (c >= '0' && c <= '9') v = c - '0';
            else if (c >= 'A' && c <= 'J') { v = c - 'A'; if (k < 3) { msgBits |= 4 >> k; custom = true; } }
            else if (c == 'K') { v = 0; if (k < 3) { msgBits |= 4 >> k; custom = true; } }
            else if (c == 'L') v = 0;
            else if (c >= 'P' && c <= 'Y') { v = c - 'P'; if (k < 3) msgBits |= 4 >> k; }
            else if (c == 'Z') { v = 0; if (k < 3) msgBits |= 4 >> k; }
            else return;
            digit[k] = v;
        }
        boolean north = isPZ(d.charAt(3));
        boolean lonOffset = isPZ(d.charAt(4));
        boolean west = isPZ(d.charAt(5));
        double latDeg = digit[0] * 10 + digit[1];
        double latMin = digit[2] * 10 + digit[3] + (digit[4] * 10 + digit[5]) / 100.0;
        double lat = latDeg + latMin / 60.0;
        if (!north) lat = -lat;

        int lonDeg = (s.charAt(1) & 0xFF) - 28;
        if (lonOffset) lonDeg += 100;
        if (lonDeg >= 180 && lonDeg <= 189) lonDeg -= 80;
        else if (lonDeg >= 190 && lonDeg <= 199) lonDeg -= 190;
        int lonMin = (s.charAt(2) & 0xFF) - 28;
        if (lonMin >= 60) lonMin -= 60;
        int lonHun = (s.charAt(3) & 0xFF) - 28;
        double lon = lonDeg + (lonMin + lonHun / 100.0) / 60.0;
        if (west) lon = -lon;
        if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
            p.lat = lat;
            p.lon = lon;
        }

        int sp = (s.charAt(4) & 0xFF) - 28;
        int dc = (s.charAt(5) & 0xFF) - 28;
        int se = (s.charAt(6) & 0xFF) - 28;
        int speed = sp * 10 + dc / 10;
        if (speed >= 800) speed -= 800;
        int course = (dc % 10) * 100 + se;
        if (course >= 400) course -= 400;
        p.speedKnots = speed;
        if (course > 0 && course <= 360) p.courseDeg = course % 360;
        p.symCode = s.charAt(7);
        p.symTable = s.charAt(8);

        // message bits A,B,C: 111 = Off Duty (or Custom-0) ... 000 = Emergency
        p.miceStatus = (custom ? MICE_CUSTOM : MICE_STD)[msgBits];

        String rest = s.substring(9);
        // optional type byte, then optional "xxx}" altitude (base 91, metres + 10000)
        if (!rest.isEmpty() && "`'>]".indexOf(rest.charAt(0)) >= 0
                && rest.length() >= 5 && rest.charAt(4) == '}') rest = rest.substring(1);
        if (rest.length() >= 4 && rest.charAt(3) == '}') {
            try {
                p.altitudeM = base91(rest.substring(0, 3)) - 10000;
                rest = rest.substring(4);
            } catch (IllegalArgumentException ignored) { }
        }
        // strip common radio-type suffixes (Kenwood "=" / "^", Yaesu "_ ")
        rest = rest.replaceAll("(_[\\x20-\\x7e]|_%|\\^|=)$", "");
        if (!rest.isEmpty() && (rest.charAt(0) == '>' || rest.charAt(0) == ']' || rest.charAt(0) == '`'
                || rest.charAt(0) == '\'')) rest = rest.substring(1);
        p.comment = altitudeFrom(p, rest);
    }

    private static boolean isPZ(char c) {
        return c >= 'P' && c <= 'Z';
    }
}
