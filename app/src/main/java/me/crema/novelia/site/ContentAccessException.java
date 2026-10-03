package me.crema.novelia.site;

import java.io.IOException;

/** A fixed, actionable access error; never retains a response or account data. */
public final class ContentAccessException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Reason { LOGIN_REQUIRED, AGE_VERIFICATION_REQUIRED, ADULT_MODE_REQUIRED, AGE_RESTRICTED }
    public final Reason reason;

    public ContentAccessException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    private static String message(Reason reason) {
        switch (reason) {
            case LOGIN_REQUIRED: return "로그인이 필요합니다. 로그인 후 다시 시도해주세요.";
            case AGE_VERIFICATION_REQUIRED: return "노벨피아에서 성인/본인인증을 완료한 후 다시 시도해주세요.";
            case ADULT_MODE_REQUIRED: return "성인 모드가 꺼져 있습니다. 계정 메뉴에서 성인 모드를 켜주세요.";
            case AGE_RESTRICTED: return "청소년은 성인 작품을 이용하실 수 없습니다.";
            default: throw new IllegalArgumentException("Access reason required");
        }
    }
}
