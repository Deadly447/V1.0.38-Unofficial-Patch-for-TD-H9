package net.kg7lbj.h9monitor.aprs;

/** What one APRS packet says. Missing numbers are NaN; missing text is null. */
public final class AprsPacket {
    public enum Type { POSITION, MIC_E, OBJECT, ITEM, STATUS, MESSAGE, WEATHER, TELEMETRY, OTHER }

    public Type type = Type.OTHER;
    /** Station the packet is about: the sender, or the object/item name. */
    public String name;
    /** Station that sent it (differs from name for objects and items). */
    public String sender;
    public String dest;
    public String path;
    public String heardVia;      // null = heard direct
    public boolean thirdParty;   // came wrapped in a } packet (relayed by an iGate)

    public double lat = Double.NaN, lon = Double.NaN;
    public int ambiguity;        // 0-4 digits blanked
    public char symTable = '/', symCode = ' ';
    public double courseDeg = Double.NaN;
    public double speedKnots = Double.NaN;
    public double altitudeM = Double.NaN;
    public String comment;

    public boolean killed;       // object/item deleted
    public String status;
    public String msgTo, msgText, msgId;
    public String miceStatus;    // Mic-E message, e.g. "En Route"
    public Weather wx;
    public String telemetry;
    public String raw;           // TNC2 text of the frame

    public boolean hasPosition() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    public static final class Weather {
        public double windDir = Double.NaN, windMph = Double.NaN, gustMph = Double.NaN;
        public double tempF = Double.NaN, rain1hIn = Double.NaN, rain24hIn = Double.NaN;
        public double rainMidnightIn = Double.NaN, humidity = Double.NaN, pressureMb = Double.NaN;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (!Double.isNaN(tempF)) sb.append(Math.round(tempF)).append("°F ");
            if (!Double.isNaN(windMph)) {
                sb.append("wind ");
                if (!Double.isNaN(windDir)) sb.append(Format.compass(windDir)).append(' ');
                sb.append(Math.round(windMph)).append(" mph ");
            }
            if (!Double.isNaN(gustMph)) sb.append("gust ").append(Math.round(gustMph)).append(" ");
            if (!Double.isNaN(humidity)) sb.append(Math.round(humidity)).append("% RH ");
            if (!Double.isNaN(pressureMb)) sb.append(String.format("%.1f mb ", pressureMb));
            if (!Double.isNaN(rain24hIn)) sb.append(String.format("rain24h %.2f\" ", rain24hIn));
            return sb.toString().trim();
        }
    }
}
