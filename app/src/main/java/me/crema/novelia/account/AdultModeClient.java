package me.crema.novelia.account;

import me.crema.novelia.net.NativeHttp;
import me.crema.novelia.site.ContentAccessException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reproduces the site's explicit member_adt_mode toggle, never age verification. */
public final class AdultModeClient {
    private static final String HOME = "https://novelpia.com/";
    private static final String ENDPOINT = HOME + "proc/member_adt_mode";
    private static final int MAX_HTML = 2 * 1024 * 1024;
    private static final Pattern TOP_DATA = Pattern.compile(
            "\\b(?:const|let|var)\\s+_top_obj\\s*=\\s*\\{\\s*data\\s*:\\s*\\{([^{}]*)\\}");
    private static final Pattern MEMBER = Pattern.compile("\\bmem_no\\s*:\\s*\"([0-9]{1,12})\"");
    private static final Pattern ADULT = Pattern.compile("\\bmem_adt\\s*:\\s*\"([01])\"");
    private static final Pattern BIRTH_YEAR = Pattern.compile("\\bmem_birthday\\s*:\\s*\"([0-9]{4})-[^\"]*\"");
    private static final Pattern SWITCH = Pattern.compile(
            "^_top_obj\\.methods\\.adt_mode\\s*\\(\\s*(['\"])(on|off)\\1\\s*\\)\\s*;?$");
    private final HttpAgent http;

    public AdultModeClient(final NativeHttp http) {
        if (http == null) throw new IllegalArgumentException("NativeHttp required");
        this.http = new HttpAgent() {
            public String get(String url) throws IOException { return http.get(url); }
            public String post(String url, Map<String, String> form) throws IOException {
                return http.postAjax(url, form);
            }
        };
    }
    AdultModeClient(HttpAgent http) {
        if (http == null) throw new IllegalArgumentException("HttpAgent required");
        this.http = http;
    }

    public AdultModeStatus status() throws IOException { return inspect(http.get(HOME)).status; }

    /** Call only on the user's explicit on/off action; one mutation, then server readback. */
    public AdultModeStatus setEnabled(boolean enabled) throws IOException {
        Page before = inspect(http.get(HOME));
        if (before.status.state == AdultModeStatus.State.LOGIN_REQUIRED)
            throw new ContentAccessException(ContentAccessException.Reason.LOGIN_REQUIRED);
        if (!before.status.isKnown()) throw new IOException("성인 모드 상태를 확인하지 못했습니다. 새로고침해주세요.");
        if (before.status.isEnabled() == enabled) return before.status;
        // The website rejects known minors before submitting its toggle. Replicate
        // that guard for enabling only. Server authentication remains authoritative.
        if (enabled && before.ageRestricted)
            throw new ContentAccessException(ContentAccessException.Reason.AGE_RESTRICTED);
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("option", enabled ? "on" : "off");
        String body = http.post(ENDPOINT, form);
        String result = body == null ? "" : body.trim();
        if ("login".equals(result))
            throw new ContentAccessException(ContentAccessException.Reason.LOGIN_REQUIRED);
        if ("auth".equals(result))
            throw new ContentAccessException(ContentAccessException.Reason.AGE_VERIFICATION_REQUIRED);
        if (!"OK".equals(result)) throw new IOException("성인 모드 변경을 확인하지 못했습니다. 다시 시도해주세요.");
        AdultModeStatus after = status();
        if (after.state == AdultModeStatus.State.LOGIN_REQUIRED)
            throw new ContentAccessException(ContentAccessException.Reason.LOGIN_REQUIRED);
        if (!after.isKnown() || after.isEnabled() != enabled)
            throw new IOException("변경 후 성인 모드 상태를 확인하지 못했습니다. 새로고침해주세요.");
        return after;
    }

    static AdultModeStatus parseStatus(String html) { return inspect(html).status; }
    private static Page inspect(String html) {
        Page unknown = new Page(AdultModeStatus.State.UNKNOWN, false);
        if (html == null || html.isEmpty() || html.length() > MAX_HTML) return unknown;
        Document doc = Jsoup.parse(html);
        String data = null;
        for (Element script : doc.select("script:not([src])")) {
            Matcher top = TOP_DATA.matcher(script.data());
            while (top.find()) {
                if (data != null) return unknown;
                data = top.group(1);
            }
        }
        if (data == null) return unknown;
        Matcher member = MEMBER.matcher(data);
        if (!member.find()) return unknown;
        String memberNo = member.group(1);
        if (member.find()) return unknown;
        if (memberNo.matches("0+")) return new Page(AdultModeStatus.State.LOGIN_REQUIRED, false);
        AdultModeStatus.State state = null;
        for (Element toggle : doc.select(".switch-adult")) {
            Matcher target = SWITCH.matcher(toggle.attr("onclick").trim());
            if (!target.matches()) return unknown;
            AdultModeStatus.State next = "on".equals(target.group(2))
                    ? AdultModeStatus.State.OFF : AdultModeStatus.State.ON;
            if (state != null && state != next) return unknown;
            state = next;
        }
        if (state == null) return unknown;
        Matcher adult = ADULT.matcher(data);
        Matcher birth = BIRTH_YEAR.matcher(data);
        boolean restricted = false;
        if (adult.find() && "0".equals(adult.group(1)) && birth.find()) {
            int year = Integer.parseInt(birth.group(1));
            int now = Calendar.getInstance().get(Calendar.YEAR);
            restricted = year > 1900 && year < now;
        }
        return new Page(state, restricted);
    }
    private static final class Page {
        final AdultModeStatus status;
        final boolean ageRestricted;
        Page(AdultModeStatus.State state, boolean ageRestricted) {
            this.status = new AdultModeStatus(state);
            this.ageRestricted = ageRestricted;
        }
    }
    interface HttpAgent {
        String get(String url) throws IOException;
        String post(String url, Map<String, String> form) throws IOException;
    }
}
