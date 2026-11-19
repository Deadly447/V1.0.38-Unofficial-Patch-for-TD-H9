package net.kg7lbj.h9monitor.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Frames captured from a TD-H9 over BLE (nRF Connect log, 2026-11-18), split the way they arrived. */
public class DecoderTest {

    static byte[] hex(String s) {
        String[] parts = s.trim().split("[- ]+");
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }

    static final String[][] CAPTURED = {
        {   // ABAJO telemetry, path WIDE2-1
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-82-84-82-94-9E-40-60",
            "AE-92-88-8A-64-40-63-03-F0-54-23-31-38-35-2C-31-37-34-2C-30",
            "32-39-2C-30-30-31-2C-30-35-37-2C-30-30-30-2C-30-30-30-30-30",
            "30-30-30-C0-C0-C0"},
        {   // BALD telemetry, digipeated by ABAJO
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-84-82-98-88-40-40-60",
            "82-84-82-94-9E-40-E0-AE-92-88-8A-64-40-E1-03-F0-54-23-31-30",
            "38-2C-31-34-38-2C-30-33-30-2C-30-30-34-2C-30-39-33-2C-30-30",
            "30-2C-30-30-30-30-30-30-30-30-C0-C0-C0"},
        {   // ABAJO weather-station position
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-82-84-82-94-9E-40-60",
            "AE-92-88-8A-64-40-63-03-F0-40-31-38-30-38-32-37-7A-33-37-35",
            "30-2E-34-30-4E-54-31-30-39-32-37-2E-36-37-57-26-57-58-33-69",
            "6E-31-4D-69-6E-69-20-55-3D-31-33-2E-36-56-20-6E-30-6E-68-6A",
            "C0-C0-C0"},
    };

    static List<byte[]> frames(String[] chunks) {
        Kiss k = new Kiss();
        List<byte[]> out = new ArrayList<>();
        for (String c : chunks) out.addAll(k.feed(hex(c)));
        return out;
    }

    @Test
    public void kissJoinsChunksAndIgnoresExtraFends() {
        for (String[] cap : CAPTURED) assertEquals(1, frames(cap).size());
        // one frame split mid-escape
        Kiss k = new Kiss();
        assertEquals(0, k.feed(hex("C0 00 41 DB")).size());
        List<byte[]> f = k.feed(hex("DC 42 C0"));
        assertEquals(1, f.size());
        assertEquals(3, f.get(0).length);
        assertEquals((byte) 0xC0, f.get(0)[1]);
    }

    @Test
    public void telemetryDirect() {
        Ax25 f = Ax25.parse(frames(CAPTURED[0]).get(0));
        assertNotNull(f);
        assertEquals("ABAJO", f.source);
        assertEquals("APMI04", f.dest);
        assertEquals("[WIDE2-1]", f.path.toString());
        assertTrue(f.heardDirect());
        assertNull(f.heardVia());
        AprsPacket p = AprsParser.parse(f);
        assertEquals(AprsPacket.Type.TELEMETRY, p.type);
        assertEquals("185,174,029,001,057,000,00000000", p.telemetry);
        assertEquals("ABAJO>APMI04,WIDE2-1:T#185,174,029,001,057,000,00000000", p.raw);
    }

    @Test
    public void telemetryViaDigi() {
        Ax25 f = Ax25.parse(frames(CAPTURED[1]).get(0));
        assertEquals("BALD", f.source);
        assertEquals("[ABAJO*, WIDE2*]", f.path.toString());
        assertFalse(f.heardDirect());
        assertEquals("ABAJO", f.heardVia());
    }

    @Test
    public void weatherStationPosition() {
        AprsPacket p = AprsParser.parse(Ax25.parse(frames(CAPTURED[2]).get(0)));
        assertEquals(AprsPacket.Type.POSITION, p.type);
        assertEquals(37 + 50.40 / 60, p.lat, 1e-9);
        assertEquals(-(109 + 27.67 / 60), p.lon, 1e-9);
        assertEquals('T', p.symTable);
        assertEquals('&', p.symCode);
        assertEquals("WX3in1Mini U=13.6V n0nhj", p.comment);
    }

    static AprsPacket tnc2(String line) {
        int c = line.indexOf(':');
        Ax25 f = Ax25.fromTnc2(line.substring(0, c), line.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1));
        return AprsParser.parse(f);
    }

    @Test
    public void compressedPosition() {
        // APRS 1.0.1 spec example: /5L!!<*e7>7P[  = 49 30' N, 72 45' W, course 88, 36.2 knots
        AprsPacket p = tnc2("N0CALL>APRS:=/5L!!<*e7>7P[");
        assertEquals(49.5, p.lat, 1e-4);
        assertEquals(-72.75, p.lon, 1e-4);
        assertEquals(88, p.courseDeg, 1e-9);
        assertEquals(36.2, p.speedKnots, 0.1);
        assertEquals('>', p.symCode);
    }

    @Test
    public void plainWithCourseSpeedAltitude() {
        AprsPacket p = tnc2("KG7LBJ-9>APRS,WIDE1-1:!3752.50N/10925.00W>090/045/A=007200 hello");
        assertEquals(90, p.courseDeg, 1e-9);
        assertEquals(45, p.speedKnots, 1e-9);
        assertEquals(7200 * 0.3048, p.altitudeM, 1e-6);
        assertEquals("hello", p.comment);
    }

    @Test
    public void weatherReport() {
        AprsPacket p = tnc2("WX1>APRS:@092345z4903.50N/07201.75W_220/004g005t077r000p000P000h50b09900wRSW");
        assertEquals(AprsPacket.Type.WEATHER, p.type);
        assertEquals(220, p.wx.windDir, 1e-9);
        assertEquals(4, p.wx.windMph, 1e-9);
        assertEquals(77, p.wx.tempF, 1e-9);
        assertEquals(50, p.wx.humidity, 1e-9);
        assertEquals(990.0, p.wx.pressureMb, 1e-9);
    }

    @Test
    public void objectItemStatusMessage() {
        AprsPacket o = tnc2("KG7LBJ>APRS:;LEADER   *092345z4903.50N/07201.75W>088/036");
        assertEquals(AprsPacket.Type.OBJECT, o.type);
        assertEquals("LEADER", o.name);
        assertEquals("KG7LBJ", o.sender);
        AprsPacket i = tnc2("KG7LBJ>APRS:)AID #2!4903.50N/07201.75WA");
        assertEquals(AprsPacket.Type.ITEM, i.type);
        assertEquals("AID #2", i.name);
        AprsPacket s = tnc2("KG7LBJ>APRS:>092345zNet control tonight");
        assertEquals("Net control tonight", s.status);
        AprsPacket m = tnc2("KG7LBJ>APRS::N0CALL-5 :Hello there{12");
        assertEquals(AprsPacket.Type.MESSAGE, m.type);
        assertEquals("N0CALL-5", m.msgTo);
        assertEquals("Hello there", m.msgText);
        assertEquals("12", m.msgId);
    }

    @Test
    public void thirdParty() {
        AprsPacket p = tnc2("IGATE>APRS:}N0CALL>APRS,TCPIP,IGATE*:!3752.50N/10925.00W-home");
        assertTrue(p.thirdParty);
        assertEquals("N0CALL", p.name);
        assertEquals("home", p.comment);
    }

    @Test
    public void storeKeepsLatest() {
        StationStore st = new StationStore();
        for (String[] cap : CAPTURED) st.add(1000, frames(cap).get(0));
        assertEquals(2, st.size());
        Station a = st.get("ABAJO");
        assertEquals(2, a.count);
        assertNotNull(a.lastPosition);
        assertEquals(3, st.log().size());
    }
}
