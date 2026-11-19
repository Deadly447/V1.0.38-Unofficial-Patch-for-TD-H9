package net.kg7lbj.h9monitor.aprs;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Display helpers (US units). */
public final class Format {
    private Format() {}

    private static final String[] DIRS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    public static String compass(double deg) {
        int i = (int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16;
        return DIRS[i];
    }

    public static String latLon(double lat, double lon) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    /** Degrees and decimal minutes, the way APRS and most radios show it. */
    public static String dm(double lat, double lon) {
        return dmOne(Math.abs(lat), lat >= 0 ? 'N' : 'S', 2) + "  " + dmOne(Math.abs(lon), lon >= 0 ? 'E' : 'W', 3);
    }

    private static String dmOne(double v, char hemi, int degDigits) {
        int d = (int) v;
        double m = (v - d) * 60;
        if (m >= 59.995) { d++; m = 0; }
        return String.format(Locale.US, "%0" + degDigits + "d°%05.2f' %c", d, m, hemi);
    }

    public static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h " + (s % 3600 / 60) + "m ago";
        return (s / 86400) + "d ago";
    }

    /** APRS text is ASCII but some stations send UTF-8; show it properly when it is. */
    public static String text(String latin1) {
        if (latin1 == null) return null;
        byte[] b = latin1.getBytes(StandardCharsets.ISO_8859_1);
        boolean high = false;
        for (byte x : b) if ((x & 0x80) != 0) { high = true; break; }
        if (!high) return latin1;
        String u = new String(b, StandardCharsets.UTF_8);
        return u.indexOf('�') >= 0 ? latin1 : u;
    }

    public static String speedCourse(double knots, double course) {
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(knots)) sb.append(Math.round(knots * 1.15078)).append(" mph");
        if (!Double.isNaN(course) && !(course == 0 && (Double.isNaN(knots) || knots == 0))) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("heading ").append(compass(course)).append(" (").append(Math.round(course)).append("°)");
        }
        return sb.toString();
    }

    public static String altitude(double m) {
        return Math.round(m * 3.28084) + " ft";
    }
}
