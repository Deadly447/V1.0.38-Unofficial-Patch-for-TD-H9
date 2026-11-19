package net.kg7lbj.h9monitor.aprs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Heard stations and the raw packet log. Not thread-safe: use from one thread. */
public final class StationStore {
    public static final int KEEP_LOG = 1000;

    private final Map<String, Station> stations = new LinkedHashMap<>();
    private final Deque<Station.Heard> log = new ArrayDeque<>();
    private long lastPacketTime;

    /** Decodes and records one AX.25 frame. Returns the packet, or null if it was not a UI frame. */
    public AprsPacket add(long now, byte[] ax25) {
        Ax25 f = Ax25.parse(ax25);
        if (f == null) return null;
        AprsPacket p = AprsParser.parse(f);
        add(now, p);
        return p;
    }

    public void add(long now, AprsPacket p) {
        lastPacketTime = now;
        String key = p.name == null || p.name.isEmpty() ? p.sender : p.name;
        Station s = stations.get(key);
        if (s == null) {
            s = new Station(key);
            stations.put(key, s);
        }
        s.add(now, p);
        log.addFirst(new Station.Heard(now, p));
        while (log.size() > KEEP_LOG) log.removeLast();
    }

    /** Stations, most recently heard first. */
    public List<Station> stations() {
        List<Station> l = new ArrayList<>(stations.values());
        Collections.sort(l, (a, b) -> Long.compare(b.lastHeard, a.lastHeard));
        return l;
    }

    public Station get(String name) { return stations.get(name); }

    public List<Station.Heard> log() { return new ArrayList<>(log); }

    public int size() { return stations.size(); }

    public long lastPacketTime() { return lastPacketTime; }

    public void clear() {
        stations.clear();
        log.clear();
    }
}
