package net.kg7lbj.h9monitor.aprs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins KISS bytes that arrive in pieces (BLE notifications are 20 bytes)
 * into whole frames. Extra FENDs between frames are ignored.
 */
public final class Kiss {
    public static final int FEND = 0xC0, FESC = 0xDB, TFEND = 0xDC, TFESC = 0xDD;
    private static final int MAX_FRAME = 1024;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private boolean inFrame = false;
    private boolean escape = false;

    /** Feed received bytes; returns the AX.25 payloads of any complete data frames. */
    public List<byte[]> feed(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        if (data == null) return out;
        for (byte bb : data) {
            int b = bb & 0xFF;
            if (b == FEND) {
                if (inFrame && buf.size() > 0) {
                    byte[] f = buf.toByteArray();
                    // first byte is port/command; 0x00 = data on port 0 (any port accepted)
                    if ((f[0] & 0x0F) == 0 && f.length > 1) {
                        byte[] ax = new byte[f.length - 1];
                        System.arraycopy(f, 1, ax, 0, ax.length);
                        out.add(ax);
                    }
                }
                buf.reset();
                inFrame = true;
                escape = false;
                continue;
            }
            if (!inFrame) continue;
            if (escape) {
                if (b == TFEND) b = FEND;
                else if (b == TFESC) b = FESC;
                escape = false;
            } else if (b == FESC) {
                escape = true;
                continue;
            }
            if (buf.size() >= MAX_FRAME) {   // runaway frame: drop it
                buf.reset();
                inFrame = false;
                continue;
            }
            buf.write(b);
        }
        return out;
    }

    /** Wraps an AX.25 frame as a KISS data frame for port 0. */
    public static byte[] encode(byte[] ax25) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(ax25.length + 8);
        o.write(FEND);
        o.write(0x00);
        for (byte bb : ax25) {
            int b = bb & 0xFF;
            if (b == FEND) { o.write(FESC); o.write(TFEND); }
            else if (b == FESC) { o.write(FESC); o.write(TFESC); }
            else o.write(b);
        }
        o.write(FEND);
        return o.toByteArray();
    }

    public void reset() {
        buf.reset();
        inFrame = false;
        escape = false;
    }
}
