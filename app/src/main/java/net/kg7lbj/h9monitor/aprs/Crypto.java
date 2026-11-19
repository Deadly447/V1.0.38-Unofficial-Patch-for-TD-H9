package net.kg7lbj.h9monitor.aprs;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Message encryption for research and bench testing.
 *
 * NOTE: transmitting encrypted messages on amateur frequencies is prohibited
 * in the US (47 CFR 97.113(a)(4)) and in most other countries.
 *
 * Format of the message text on the air: "ENC1:" + base64url(nonce12 | ciphertext | tag16),
 * AES-256-GCM, with "FROM>TO" (upper case callsigns) as associated data so a
 * message can't be replayed as coming from, or going to, another station.
 * The key is PBKDF2-HMAC-SHA256 of the shared passphrase.
 */
public final class Crypto {
    private Crypto() {}

    public static final String PREFIX = "ENC1:";
    public static final int MAX_PLAIN_BYTES = 120;    // keeps the APRS info field under ~220 bytes
    static final int ITERATIONS = 120_000;
    private static final int NONCE = 12, TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    /** Derives the 256-bit key from a passphrase. Slow on purpose: call once and keep the key. */
    public static byte[] deriveKey(String passphrase) {
        try {
            byte[] salt = MessageDigest.getInstance("SHA-256")
                    .digest("H9Monitor message key v1".getBytes(StandardCharsets.US_ASCII));
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Short fingerprint so both people can check they typed the same passphrase. */
    public static String fingerprint(byte[] key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
            return String.format(Locale.US, "%02X%02X-%02X%02X", h[0], h[1], h[2], h[3]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean looksEncrypted(String text) {
        return text != null && text.startsWith(PREFIX);
    }

    public static String encrypt(byte[] key, String from, String to, String plain) {
        byte[] p = plain.getBytes(StandardCharsets.UTF_8);
        if (p.length > MAX_PLAIN_BYTES) throw new IllegalArgumentException("Message is too long to encrypt");
        try {
            byte[] nonce = new byte[NONCE];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(from, to));
            byte[] ct = c.doFinal(p);
            byte[] out = new byte[NONCE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(ct, 0, out, NONCE, ct.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the plain text, or null if the key is wrong or the message was altered. */
    public static String decrypt(byte[] key, String from, String to, String wire) {
        if (key == null || !looksEncrypted(wire)) return null;
        try {
            byte[] in = Base64.getUrlDecoder().decode(wire.substring(PREFIX.length()).trim());
            if (in.length < NONCE + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, NONCE));
            c.updateAAD(aad(from, to));
            byte[] p = c.doFinal(in, NONCE, in.length - NONCE);
            return new String(p, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] aad(String from, String to) {
        return (from.trim().toUpperCase(Locale.US) + ">" + to.trim().toUpperCase(Locale.US))
                .getBytes(StandardCharsets.US_ASCII);
    }
}
