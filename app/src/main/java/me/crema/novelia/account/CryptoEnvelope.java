package me.crema.novelia.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure encrypt-then-MAC envelope used to protect saved account credentials.
 *
 * <p>This class never touches the AndroidKeyStore. It encrypts a payload with
 * a caller-supplied 256-bit {@code dataKey}, authenticates the ciphertext with a
 * separate 256-bit {@code macKey}, applies a versioned binary framing, and both
 * verifies the MAC with {@link MessageDigest#isEqual} before decryption.
 *
 * <p>Design (all fixed sizes stored big-endian):
 * <ul>
 *   <li>envelope = version(1) | ivLen(2) | cipherLen(4) | iv | mac(32) | ciphertext</li>
 *   <li>AES-256/CBC/PKCS5Padding with a random 16-byte IV</li>
 *   <li>HMAC-SHA256 over version | ivLen | cipherLen | iv | ciphertext</li>
 * </ul>
 *
 * <p>The MAC key is unrelated to the data key, so a ciphertext plus its own MAC
 * key (e.g. a wrong fallback path) can never decrypt. No secret material is ever
 * included in exceptions, and every tampered/truncated envelope fails closed.
 */
public final class CryptoEnvelope {

    static final int VERSION = 1;
    static final int AES_KEY_BYTES = 32; // AES-256
    static final int MAC_KEY_BYTES = 32; // HMAC-SHA256
    static final int IV_BYTES = 16;
    static final int MAC_BYTES = 32;
    private static final int MAX_CIPHER_LEN = 8192;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String CIPHER_TRANSFORM = "AES/CBC/PKCS5Padding";

    private CryptoEnvelope() {
        throw new AssertionError("no instances");
    }

    /** UTF-8 payload bytes, rejecting malformed surrogate/encoding input. */
    static byte[] encodeUtf8(String text) throws IOException {
        if (text == null) throw new IOException("missing input");
        final java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text));
        final byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    static byte[] encrypt(byte[] dataKey, byte[] macKey, byte[] payload)
            throws GeneralSecurityException, IOException {
        requireKey("data key", dataKey, AES_KEY_BYTES);
        requireKey("MAC key", macKey, MAC_KEY_BYTES);
        if (payload == null || payload.length == 0 || payload.length >= MAX_CIPHER_LEN)
            throw new IOException("invalid payload length");

        final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        final byte[] iv = new byte[IV_BYTES];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new IvParameterSpec(iv));
        final byte[] ciphertext = cipher.doFinal(payload);
        if (ciphertext.length == 0 || ciphertext.length > MAX_CIPHER_LEN) throw new IOException("bad ciphertext");

        final byte[] header = headerFor(iv, ciphertext.length);
        final byte[] envelope = Arrays.copyOf(header, header.length + MAC_BYTES + ciphertext.length);
        System.arraycopy(mac(macKey, plaintextToMac(header, ciphertext)), 0, envelope, header.length, MAC_BYTES);
        System.arraycopy(ciphertext, 0, envelope, header.length + MAC_BYTES, ciphertext.length);
        return envelope;
    }

    /** Decrypt and verify. Throws {@link GeneralSecurityException} on any integrity failure. */
    static byte[] decrypt(byte[] dataKey, byte[] macKey, byte[] envelope)
            throws GeneralSecurityException, IOException {
        requireKey("data key", dataKey, AES_KEY_BYTES);
        requireKey("MAC key", macKey, MAC_KEY_BYTES);
        if (envelope == null) throw new IOException("missing envelope");
        final int minimum = 1 + 2 + 4 + IV_BYTES + MAC_BYTES + 1;
        if (envelope.length < minimum) throw new GeneralSecurityException("corrupt credential blob");
        if (envelope[0] != (byte) VERSION) throw new GeneralSecurityException("unsupported credential version");
        final int ivLen = readU16(envelope, 1);
        final long cipherLenLong = readU32(envelope, 3);
        if (cipherLenLong > Integer.MAX_VALUE) throw new GeneralSecurityException("corrupt credential blob");
        final int cipherLen = (int) cipherLenLong;
        if (ivLen != IV_BYTES) throw new GeneralSecurityException("corrupt credential blob");
        if (cipherLen <= 0) throw new GeneralSecurityException("corrupt credential blob");
        final long headerLen = 1L + 2L + 4L + (long) ivLen;
        final long bodyLength = headerLen + MAC_BYTES + (long) cipherLen;
        if (cipherLen > MAX_CIPHER_LEN || bodyLength != (long) envelope.length) {
            throw new GeneralSecurityException("corrupt credential blob");
        }
        final byte[] header = Arrays.copyOfRange(envelope, 0, (int) headerLen);
        final byte[] ciphertext = Arrays.copyOfRange(envelope, (int) (headerLen + MAC_BYTES), (int) bodyLength);
        final byte[] expectedMac = Arrays.copyOfRange(envelope, (int) headerLen, (int) (headerLen + MAC_BYTES));

        final byte[] actualMac = mac(macKey, plaintextToMac(header, ciphertext));
        if (!MessageDigest.isEqual(expectedMac, actualMac)) {
            throw new GeneralSecurityException("corrupt credential blob");
        }
        final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(header, 7, 7 + IV_BYTES)));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] headerFor(byte[] iv, int cipherLen) {
        final byte[] header = new byte[1 + 2 + 4 + iv.length];
        header[0] = (byte) VERSION;
        writeU16(header, 1, iv.length);
        writeU32(header, 3, cipherLen);
        System.arraycopy(iv, 0, header, 7, iv.length);
        return header;
    }

    /** MAC input is version | ivLen | cipherLen | iv | ciphertext (no PKCS5 tail). */
    private static byte[] plaintextToMac(byte[] header, byte[] ciphertext) {
        final byte[] macInput = Arrays.copyOf(header, header.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, macInput, header.length, ciphertext.length);
        return macInput;
    }

    private static byte[] mac(byte[] macKey, byte[] input) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(macKey, HMAC_ALGORITHM));
        return mac.doFinal(input);
    }

    private static void requireKey(String name, byte[] key, int bytes) throws IOException {
        if (key == null || key.length != bytes) throw new IOException("invalid " + name + " length");
    }

    private static int readU16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long readU32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (long) (bytes[offset + 3] & 0xff);
    }

    private static void writeU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void writeU32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
