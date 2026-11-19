package net.kg7lbj.h9monitor.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MessengerTest {

    /** Two phones on one simulated channel: whatever one transmits, the other hears. */
    static final class Phone implements Messenger.Config, Messenger.Radio {
        final String call;
        final List<byte[]> sent = new ArrayList<>();
        boolean connected = true;
        byte[] key;
        boolean encrypt;
        Messenger m;

        Phone(String call) { this.call = call; m = new Messenger(this, this); }
        @Override public String myCall() { return call; }
        @Override public List<String> path() { return Arrays.asList("WIDE1-1", "NOGATE"); }
        @Override public byte[] keyFor(String peer) { return key; }
        @Override public boolean encryptTo(String peer) { return encrypt; }
        @Override public boolean transmit(byte[] f) { if (!connected) return false; sent.add(f); return true; }

        /** Decode what we sent, through the same KISS and AX.25 code the radio link uses. */
        List<AprsPacket> drain() {
            List<AprsPacket> out = new ArrayList<>();
            Kiss k = new Kiss();
            for (byte[] f : sent) for (byte[] ax : k.feed(Kiss.encode(f))) out.add(AprsParser.parse(Ax25.parse(ax)));
            sent.clear();
            return out;
        }
    }

    static void deliver(Phone from, Phone to, long now) {
        for (AprsPacket p : from.drain()) to.m.onPacket(p, now);
    }

    @Test
    public void frameOnTheAir() {
        Phone a = new Phone("KG7LBJ-7");
        a.m.send("n0call-5", "Hello from the trail", 0);
        AprsPacket p = a.drain().get(0);
        assertEquals("KG7LBJ-7>APZH9M,WIDE1-1,NOGATE::N0CALL-5 :Hello from the trail{1", p.raw);
        assertEquals(AprsPacket.Type.MESSAGE, p.type);
        assertEquals("N0CALL-5", p.msgTo);
        assertEquals("1", p.msgId);
    }

    @Test
    public void sendAckDelivered() {
        Phone a = new Phone("KG7LBJ-7"), b = new Phone("N0CALL-5");
        Messenger.Msg out = a.m.send("N0CALL-5", "Meet at the trailhead", 0);
        deliver(a, b, 1000);
        List<Messenger.Msg> got = b.m.messages("KG7LBJ-7");
        assertEquals(1, got.size());
        assertEquals("Meet at the trailhead", got.get(0).text);
        deliver(b, a, 2000);                       // b's ack
        assertEquals(Messenger.State.DELIVERED, out.state);
        a.m.tick(100_000);                         // no more retries once acked
        assertTrue(a.sent.isEmpty());
    }

    @Test
    public void retriesThenFails() {
        Phone a = new Phone("KG7LBJ-7");
        Messenger.Msg out = a.m.send("N0CALL-5", "anyone?", 0);
        long t = 0;
        for (int i = 0; i < 10; i++) { t += 300_000; a.m.tick(t); }
        assertEquals(5, a.drain().size());
        assertEquals(Messenger.State.FAILED, out.state);
    }

    @Test
    public void duplicateShownOnceButAckedEachTime() {
        Phone a = new Phone("KG7LBJ-7"), b = new Phone("N0CALL-5");
        a.m.send("N0CALL-5", "copy?", 0);
        List<AprsPacket> first = a.drain();
        assertNotNull(b.m.onPacket(first.get(0), 1000));
        assertNull(b.m.onPacket(first.get(0), 31_000));   // the retry: not shown again
        assertEquals(1, b.m.messages("KG7LBJ-7").size());
        assertEquals(2, b.drain().size());                 // but acked both times
    }

    @Test
    public void notSentWhileDisconnected() {
        Phone a = new Phone("KG7LBJ-7");
        a.connected = false;
        Messenger.Msg out = a.m.send("N0CALL-5", "later", 0);
        assertEquals(0, out.sends);
        a.connected = true;
        a.m.tick(10_000);
        assertEquals(1, out.sends);
    }

    @Test
    public void ignoresOthersAndOwnEcho() {
        Phone a = new Phone("KG7LBJ-7"), b = new Phone("N0CALL-5"), c = new Phone("W1AW");
        a.m.send("W1AW", "not for b", 0);
        for (AprsPacket p : a.drain()) {
            assertNull(b.m.onPacket(p, 1));       // addressed to someone else
            assertNull(a.m.onPacket(p, 1));       // our own, heard via a digipeater
            assertNotNull(c.m.onPacket(p, 1));
        }
    }

    @Test
    public void textIsCleanedAndLimited() {
        assertEquals("abcd e", Messenger.cleanText("a|b~c{d\ne"));
        assertEquals(67, Messenger.cleanText(new String(new char[100]).replace('\0', 'x')).length());
    }

    @Test
    public void saveAndLoad() {
        Phone a = new Phone("KG7LBJ-7");
        a.m.send("N0CALL-5", "saved", 0);
        List<String> lines = a.m.save();
        Phone a2 = new Phone("KG7LBJ-7");
        a2.m.load(lines, 5000);
        assertEquals("saved", a2.m.messages("N0CALL-5").get(0).text);
        Messenger.Msg next = a2.m.send("N0CALL-5", "next", 6000);
        assertEquals("2", next.id);
    }

    static final byte[] KEY = Crypto.deriveKey("correct horse battery staple");

    @Test
    public void encryptedRoundTrip() {
        Phone a = new Phone("KG7LBJ-7"), b = new Phone("N0CALL-5");
        a.key = KEY; a.encrypt = true; b.key = KEY;
        Messenger.Msg out = a.m.send("N0CALL-5", "Rally at checkpoint 3 — 1400", 0);
        assertTrue(out.encrypted);
        AprsPacket onAir = a.drain().get(0);
        assertTrue(onAir.msgText.startsWith(Crypto.PREFIX));
        assertTrue(onAir.raw.indexOf("checkpoint") < 0);
        b.m.onPacket(onAir, 1000);
        Messenger.Msg got = b.m.messages("KG7LBJ-7").get(0);
        assertEquals("Rally at checkpoint 3 — 1400", got.text);
        assertTrue(got.encrypted);
        deliver(b, a, 2000);
        assertEquals(Messenger.State.DELIVERED, out.state);
    }

    @Test
    public void wrongOrMissingKeyStaysLocked() {
        Phone a = new Phone("KG7LBJ-7"), b = new Phone("N0CALL-5");
        a.key = KEY; a.encrypt = true;
        a.m.send("N0CALL-5", "secret", 0);
        AprsPacket p = a.drain().get(0);
        b.m.onPacket(p, 1);
        Messenger.Msg got = b.m.messages("KG7LBJ-7").get(0);
        assertTrue(got.locked);
        b.key = Crypto.deriveKey("wrong");
        b.m.rekey("KG7LBJ-7");
        assertTrue(got.locked);
        b.key = KEY;
        b.m.rekey("KG7LBJ-7");
        assertEquals("secret", got.text);
    }

    @Test
    public void tamperedOrRedirectedFails() {
        String w = Crypto.encrypt(KEY, "KG7LBJ-7", "N0CALL-5", "hello");
        assertEquals("hello", Crypto.decrypt(KEY, "KG7LBJ-7", "N0CALL-5", w));
        assertNull(Crypto.decrypt(KEY, "W1AW", "N0CALL-5", w));            // claims another sender
        assertNull(Crypto.decrypt(KEY, "KG7LBJ-7", "W1AW", w));            // replayed to another station
        char[] c = w.toCharArray();
        c[10] = c[10] == 'A' ? 'B' : 'A';
        assertNull(Crypto.decrypt(KEY, "KG7LBJ-7", "N0CALL-5", new String(c)));
    }

    @Test
    public void longestEncryptedMessageFitsTheRadio() {
        Phone a = new Phone("KG7LBJ-15");
        a.key = KEY; a.encrypt = true;
        a.m.send("N0CALL-15", new String(new char[Crypto.MAX_PLAIN_BYTES]).replace('\0', 'x'), 0);
        AprsPacket p = a.drain().get(0);
        int info = p.raw.length() - p.raw.indexOf(':') - 1;
        assertTrue("info " + info, info <= 256);
    }

    @Test
    public void savedFileHasNoPlainText() {
        Phone a = new Phone("KG7LBJ-7");
        a.key = KEY; a.encrypt = true;
        a.m.send("N0CALL-5", "meet at the cabin", 0);
        for (String line : a.m.save()) assertTrue(line.indexOf("cabin") < 0);
        Phone a2 = new Phone("KG7LBJ-7");
        a2.key = KEY;
        a2.m.load(a.m.save(), 0);
        assertEquals("meet at the cabin", a2.m.messages("N0CALL-5").get(0).text);
    }
}
