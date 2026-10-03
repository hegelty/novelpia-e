package me.crema.novelia.account;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.Test;

/**
 * JVM-only integrity tests for the pure encrypt-then-MAC envelope. No real
 * credentials are ever used: payloads are arbitrary dummy byte arrays and the
 * random keys come from {@link SecureRandom}.
 */
public class CryptoEnvelopeTest {

    private final SecureRandom random = new SecureRandom();

    private static byte[] key(int bytes) {
        final byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static byte[] fakeCredentialsPayload() {
        return "user@example.invalid+correct horse battery staple".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** An intact envelope must round-trip. */
    @Test public void roundTripPreservesPayload() throws Exception {
        final byte[] payload = fakeCredentialsPayload();
        final byte[] dataKey = key(CryptoEnvelope.AES_KEY_BYTES);
        final byte[] macKey = key(CryptoEnvelope.MAC_KEY_BYTES);
        final byte[] blob = CryptoEnvelope.encrypt(dataKey, macKey, payload);
        assertArrayEquals(payload, CryptoEnvelope.decrypt(dataKey, macKey, blob));
    }

    /** The payload is encrypted as a whole; individual equal bytes are normal. */
    @Test public void ciphertextDoesNotContainPlaintextPayload() throws Exception {
        final byte[] payload = fakeCredentialsPayload();
        final byte[] blob = CryptoEnvelope.encrypt(key(CryptoEnvelope.AES_KEY_BYTES),
                key(CryptoEnvelope.MAC_KEY_BYTES), payload);
        final int ivLen = ((blob[1] & 0xff) << 8) | (blob[2] & 0xff);
        final byte[] ciphertext = Arrays.copyOfRange(blob, 1 + 2 + 4 + ivLen + CryptoEnvelope.MAC_BYTES, blob.length);
        // PKCS5 adds a full block when the payload is already block-aligned.
        assertTrue(ciphertext.length >= payload.length);
        assertTrue(ciphertext.length % 16 == 0);
        for (int i = 0; i + payload.length <= blob.length; i++)
            assertFalse(Arrays.equals(payload, Arrays.copyOfRange(blob, i, i + payload.length)));
    }

    /** Encrypting the same payload twice must produce different envelopes. */
    @Test public void randomIvMakesEveryEnvelopeUnique() throws Exception {
        byte[] aes = key(32), mac = key(32);
        final byte[] a = CryptoEnvelope.encrypt(aes, mac, fakeCredentialsPayload());
        final byte[] b = CryptoEnvelope.encrypt(aes, mac, fakeCredentialsPayload());
        assertFalse(Arrays.equals(a, b));
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 7, 23), Arrays.copyOfRange(b, 7, 23)));
    }

    /** Any flipped ciphertext byte must fail closed before returning data. */
    @Test public void tamperingWithAnyCiphertextByteFails() throws Exception {
        final byte[] dataKey = key(CryptoEnvelope.AES_KEY_BYTES);
        final byte[] macKey = key(CryptoEnvelope.MAC_KEY_BYTES);
        final byte[] payload = fakeCredentialsPayload();
        final byte[] blob = CryptoEnvelope.encrypt(dataKey, macKey, payload);
        final int ivLen = ((blob[1] & 0xff) << 8) | (blob[2] & 0xff);
        final int cipherStart = 1 + 2 + 4 + ivLen + CryptoEnvelope.MAC_BYTES;
        for (int i = 0; i < payload.length; i += 7) {
            final byte[] tampered = blob.clone();
            tampered[cipherStart + i] ^= (byte) 0x01;
            assertFails(tampered, dataKey, macKey);
        }
    }

    /** Truncating any suffix must fail closed (length check + MAC). */
    @Test public void anySuffixTruncationFails() throws Exception {
        final byte[] dataKey = key(CryptoEnvelope.AES_KEY_BYTES);
        final byte[] macKey = key(CryptoEnvelope.MAC_KEY_BYTES);
        final byte[] blob = CryptoEnvelope.encrypt(dataKey, macKey, fakeCredentialsPayload());
        for (int cut = 1; cut < blob.length; cut += Math.max(1, blob.length / 8)) {
            assertFails(Arrays.copyOf(blob, cut), dataKey, macKey);
        }
    }

    /** Corrupting any MAC byte must fail closed (constant-time compare). */
    @Test public void corruptingAnyMacByteFails() throws Exception {
        final byte[] dataKey = key(CryptoEnvelope.AES_KEY_BYTES);
        final byte[] macKey = key(CryptoEnvelope.MAC_KEY_BYTES);
        final byte[] blob = CryptoEnvelope.encrypt(dataKey, macKey, fakeCredentialsPayload());
        final int macStart = 1 + 2 + 4 + CryptoEnvelope.IV_BYTES;
        for (int i = 0; i < CryptoEnvelope.MAC_BYTES; i += 5) {
            final byte[] tampered = blob.clone();
            tampered[macStart + i] ^= (byte) 0x40;
            assertFails(tampered, dataKey, macKey);
        }
    }

    /** Wrong keys must fail closed. */
    @Test public void wrongKeyFailsClosed() throws Exception {
        final byte[] dataKey = key(CryptoEnvelope.AES_KEY_BYTES);
        final byte[] macKey = key(CryptoEnvelope.MAC_KEY_BYTES);
        final byte[] blob = CryptoEnvelope.encrypt(dataKey, macKey, fakeCredentialsPayload());
        // EtM authenticates with the MAC key; changing only the AES key is not
        // an integrity check (random incorrect plaintext can have valid padding).
        assertFails(blob, dataKey, key(CryptoEnvelope.MAC_KEY_BYTES));
        assertFails(blob, key(CryptoEnvelope.AES_KEY_BYTES), key(CryptoEnvelope.MAC_KEY_BYTES));
    }

    /** The framing must be deterministic (fixed sizes) for instrumentation asserts. */
    @Test public void framingIsDeterministicForMaxPayload() throws Exception {
        final byte[] payload = new byte[1 + 512 + 1 + 4096]; // email + sep + password
        final byte[] blob = CryptoEnvelope.encrypt(key(CryptoEnvelope.AES_KEY_BYTES),
                key(CryptoEnvelope.MAC_KEY_BYTES), payload);
        assertEquals(CryptoEnvelope.VERSION, blob[0] & 0xff);
        assertEquals(CryptoEnvelope.IV_BYTES, (blob[1] & 0xff) << 8 | (blob[2] & 0xff));
        final long cipherLen = readU32(blob, 3);
        final int padded = (payload.length / 16 + 1) * 16;
        assertEquals(padded, cipherLen);
    }

    private static long readU32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8) | (long) (bytes[offset + 3] & 0xff);
    }

    @Test public void appendedBytesAreRejected() throws Exception {
        byte[] aes = key(32), mac = key(32);
        byte[] blob = CryptoEnvelope.encrypt(aes, mac, fakeCredentialsPayload());
        assertFails(java.util.Arrays.copyOf(blob, blob.length + 1), aes, mac);
    }

    @Test public void malformedUnicodeIsRejectedRatherThanChangingPassword() throws Exception {
        try { CryptoEnvelope.encodeUtf8("bad\\uD800".replace("\\uD800", "\uD800")); fail(); }
        catch (IOException expected) { }
        assertArrayEquals(" password ".getBytes("UTF-8"), CryptoEnvelope.encodeUtf8(" password "));
    }

    private static void assertFails(byte[] blob, byte[] dataKey, byte[] macKey)
            throws GeneralSecurityException, IOException {
        try {
            CryptoEnvelope.decrypt(dataKey, macKey, blob);
            fail("corrupt envelope was accepted");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }
}
