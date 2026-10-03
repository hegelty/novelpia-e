package me.crema.novelia.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java bounded response reading.
 *
 * <p>Kept free of Android and Conscrypt dependencies so it can be compiled and
 * tested on a plain JVM. Enforces both a compressed wire cap (raw bytes read
 * from the socket) and an inflated cap (bytes after gzip decoding), so a
 * hostile {@code Content-Encoding: gzip} bomb cannot exceed the memory budget
 * no matter how small the caller's read window is.</p>
 *
 * <p>{@link GZIPInputStream} reads ahead in buffer-sized chunks and stops as
 * soon as the last gzip member ends, so it may never touch trailing wire
 * bytes. {@link #read(InputStream, String)} therefore completes the read
 * through to the real end of the underlying stream and counts what was
 * actually transferred; any amount beyond the compressed cap is rejected
 * either during the read or by the end-of-stream check.</p>
 */
public final class BoundedBody {

    /** Byte cap for the <em>inflated</em> response body. */
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Byte cap for compressed bytes read from the wire. */
    public static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;

    private static final String GZIP = "gzip";
    private static final int BUFFER_SIZE = 8192;
    private static final int INITIAL_CAPACITY = Math.min(BUFFER_SIZE, MAX_RESPONSE_BYTES);

    private BoundedBody() {
        // Static utility.
    }

    /**
     * Reads at most {@value #MAX_RESPONSE_BYTES} inflated bytes from
     * {@code in}, using {@link #MAX_COMPRESSED_BYTES} as the raw wire cap when
     * {@code contentEncoding} is {@code gzip}. Returns the UTF-8 decoded body.
     *
     * @param contentEncoding {@code Content-Encoding} header value, or null
     * @throws IOException when a cap or encoding failure occurs
     */
    public static String read(InputStream in, String contentEncoding)
            throws IOException {
        if (in == null) {
            throw new IOException("Null response stream");
        }
        CountingInputStream counted = new CountingInputStream(in);
        InputStream wrapped = GZIP.equalsIgnoreCase(contentEncoding)
                ? new GZIPInputStream(counted, BUFFER_SIZE) : counted;
        boolean closeWrapped = wrapped != counted;
        ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_CAPACITY);
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        try {
            while (true) {
                int read = wrapped.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Response body exceeds "
                            + MAX_RESPONSE_BYTES + " bytes limit");
                }
                out.write(buffer, 0, read);
            }
            // GZIPInputStream may stop at the last gzip member boundary and
            // leave trailing wire bytes unread. Drain them through the raw
            // counting stream so the compressed cap covers the full response.
            endCheck(counted);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            if (closeWrapped) {
                closeQuietly(wrapped);
            } else {
                closeQuietly(counted);
            }
        }
    }

    /**
     * Reads to the real end of the underlying stream through the counting
     * layer. Any byte above the compressed cap raises "Compressed response
     * exceeds … bytes limit". This also triggers the compressed cap for
     * non-gzip streams that exceed it while being decoded.
     */
    private static void endCheck(CountingInputStream counted) throws IOException {
        byte[] scratch = new byte[BUFFER_SIZE];
        while (counted.read(scratch) > 0) {
            // countRaw already enforced the compressed cap.
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best-effort close; the stream is being discarded anyway.
            }
        }
    }

    /**
     * Counts every raw byte read from the underlying stream and enforces
     * {@link #MAX_COMPRESSED_BYTES}.
     */
    private static final class CountingInputStream extends FilterInputStream {
        private int rawBytes;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                countRaw(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                countRaw(read);
            }
            return read;
        }

        private void countRaw(int read) throws IOException {
            rawBytes += read;
            if (rawBytes > MAX_COMPRESSED_BYTES) {
                throw new IOException("Compressed response exceeds "
                        + MAX_COMPRESSED_BYTES + " bytes limit");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
