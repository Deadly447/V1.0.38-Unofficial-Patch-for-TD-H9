package net.kg7lbj.h9monitor.aprs;

import java.util.ArrayDeque;
import java.util.Deque;

/** Everything heard from one station (or one object/item). */
public final class Station {
    public static final int KEEP_PACKETS = 30;

    public final String name;
    public long firstHeard, lastHeard;
    public int count;
    public AprsPacket last;          // most recent packet of any kind
    public AprsPacket lastPosition;  // most recent packet with a position
    public long lastPositionTime;
    public String status;            // latest '>' status text
    public AprsPacket.Weather wx;    // latest weather
    public String lastVia;           // null = last heard direct
    public boolean object;
    public final Deque<Heard> history = new ArrayDeque<>();

    public static final class Heard {
        public final long time;
        public final AprsPacket packet;
        Heard(long time, AprsPacket packet) { this.time = time; this.packet = packet; }
    }

    public Station(String name) {
        this.name = name;
    }

    void add(long now, AprsPacket p) {
        if (count == 0) firstHeard = now;
        count++;
        lastHeard = now;
        last = p;
        lastVia = p.heardVia;
        object = p.type == AprsPacket.Type.OBJECT || p.type == AprsPacket.Type.ITEM;
        if (p.hasPosition()) { lastPosition = p; lastPositionTime = now; }
        if (p.status != null) status = p.status;
        if (p.wx != null) wx = p.wx;
        history.addFirst(new Heard(now, p));
        while (history.size() > KEEP_PACKETS) history.removeLast();
    }

    public char symTable() { return lastPosition != null ? lastPosition.symTable : '/'; }
    public char symCode() { return lastPosition != null ? lastPosition.symCode : ' '; }

    /** One line for the list: what this station said most recently. */
    public String summary() {
        AprsPacket p = last;
        StringBuilder sb = new StringBuilder();
        switch (p.type) {
            case MESSAGE:
                sb.append("Message to ").append(p.msgTo).append(": ").append(p.msgText);
                break;
            case STATUS:
                sb.append(p.status);
                break;
            case TELEMETRY:
                sb.append("Telemetry ").append(p.telemetry);
                break;
            case OTHER:
                sb.append(p.raw.substring(p.raw.indexOf(':') + 1));
                break;
            default:
                if (p.killed) sb.append("Deleted. ");
                if (p.wx != null && !p.wx.summary().isEmpty()) sb.append(p.wx.summary()).append(". ");
                String sc = Format.speedCourse(p.speedKnots, p.courseDeg);
                if (!sc.isEmpty()) sb.append(sc).append(". ");
                if (p.miceStatus != null && !"Off Duty".equals(p.miceStatus)) sb.append(p.miceStatus).append(". ");
                if (p.comment != null) sb.append(p.comment);
        }
        return sb.toString().trim();
    }
}
