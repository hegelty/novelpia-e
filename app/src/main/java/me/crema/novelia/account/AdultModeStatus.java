package me.crema.novelia.account;

/** Only server-confirmed mode state; contains no member number or birthday. */
public final class AdultModeStatus {
    public enum State { ON, OFF, LOGIN_REQUIRED, UNKNOWN }
    public final State state;
    public final String message;
    AdultModeStatus(State state) {
        this.state = state;
        switch (state) {
            case ON: message = "성인 모드 켜짐"; break;
            case OFF: message = "성인 모드 꺼짐"; break;
            case LOGIN_REQUIRED: message = "로그인이 필요합니다."; break;
            default: message = "성인 모드 상태를 확인하지 못했습니다.";
        }
    }
    public boolean isEnabled() { return state == State.ON; }
    public boolean isKnown() { return state == State.ON || state == State.OFF; }
}
