package me.crema.novelia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;

/** Strict, bounded user-selected text import. Does not close caller-owned streams. */
public final class LocalText {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_CHARS = 300000;

    private LocalText() {}

    public static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (bytes.size() + n > MAX_BYTES) throw new IOException("TXT는 1MB 이하만 열 수 있습니다.");
            bytes.write(buffer, 0, n);
        }
        byte[] raw = bytes.toByteArray();
        int offset = raw.length >= 3 && raw[0] == (byte) 0xef
                && raw[1] == (byte) 0xbb && raw[2] == (byte) 0xbf ? 3 : 0;
        String text;
        try {
            text = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, raw.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("UTF-8 텍스트만 지원합니다. 파일 인코딩을 UTF-8로 변환해주세요.", e);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.length() > MAX_CHARS) throw new IOException("한 번에 30만 자 이하만 열 수 있습니다.");
        if (text.trim().length() == 0) throw new IOException("빈 텍스트 파일입니다.");
        return text;
    }
}
