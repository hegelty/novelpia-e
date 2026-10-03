package me.crema.novelia.account;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import me.crema.novelia.site.ContentAccessException;

public class AdultModeClientTest {
    private static String page(String member, String target) {
        return "<script>const _top_obj={data:{mem_adt:\"1\",mem_no:\"" + member
                + "\",mem_birthday:\"1990-01-01\"},methods:{}};</script>"
                + "<img class='switch-adult' onclick=\"_top_obj.methods.adt_mode('" + target + "')\">";
    }
    private static final class Fake implements AdultModeClient.HttpAgent {
        final List<String> pages = new ArrayList<String>();
        String response = "OK", postUrl;
        int gets, posts;
        Map<String,String> form;
        Fake(String before, String after) { pages.add(before); pages.add(after); }
        public String get(String url) throws IOException {
            assertEquals("https://novelpia.com/", url);
            return pages.get(Math.min(gets++, pages.size() - 1));
        }
        public String post(String url, Map<String,String> data) throws IOException {
            posts++; postUrl = url; form = new LinkedHashMap<String,String>(data); return response;
        }
    }
    @Test public void state_comes_from_scoped_switch_target_and_member_marker() {
        assertEquals(AdultModeStatus.State.OFF, AdultModeClient.parseStatus(page("42", "on")).state);
        assertEquals(AdultModeStatus.State.ON, AdultModeClient.parseStatus(page("42", "off")).state);
        assertEquals(AdultModeStatus.State.LOGIN_REQUIRED, AdultModeClient.parseStatus(page("0", "on")).state);
    }
    @Test public void duplicate_desktop_mobile_switches_must_agree() {
        String off = page("42", "on");
        assertEquals(AdultModeStatus.State.OFF, AdultModeClient.parseStatus(off
                + "<img class='switch-adult' onclick=\"_top_obj.methods.adt_mode('on')\">").state);
        assertEquals(AdultModeStatus.State.UNKNOWN, AdultModeClient.parseStatus(off
                + "<img class='switch-adult' onclick=\"_top_obj.methods.adt_mode('off')\">").state);
    }
    @Test public void absent_unknown_injected_or_ambiguous_markup_is_unknown() {
        for (String html : new String[]{null, "", "<form id=login_box></form>",
                page("42", "on").replace("_top_obj=", "unrelated="),
                page("42", "on").replace("switch-adult", "unrelated"),
                page("42", "on").replace("adt_mode('on')", "adt_mode('on');fetch('/x')"),
                page("42", "on") + page("42", "on"),
                page("42", "on").replace("mem_no:\"42\"", "mem_no:\"42\",mem_no:\"0\"")}) {
            assertEquals(AdultModeStatus.State.UNKNOWN, AdultModeClient.parseStatus(html).state);
        }
    }
    @Test public void enables_with_exact_form_and_verified_readback() throws Exception {
        Fake f = new Fake(page("42", "on"), page("42", "off"));
        assertTrue(new AdultModeClient(f).setEnabled(true).isEnabled());
        assertEquals(2, f.gets); assertEquals(1, f.posts);
        assertEquals("https://novelpia.com/proc/member_adt_mode", f.postUrl);
        assertEquals(1, f.form.size()); assertEquals("on", f.form.get("option"));
    }
    @Test public void disables_with_exact_form_and_verified_readback() throws Exception {
        Fake f = new Fake(page("42", "off"), page("42", "on"));
        assertFalse(new AdultModeClient(f).setEnabled(false).isEnabled());
        assertEquals("off", f.form.get("option")); assertEquals(1, f.posts);
    }
    @Test public void already_requested_state_does_not_mutate() throws Exception {
        Fake f = new Fake(page("42", "on"), "");
        assertFalse(new AdultModeClient(f).setEnabled(false).isEnabled());
        assertEquals(0, f.posts); assertEquals(1, f.gets);
    }
    @Test public void signed_out_never_posts() throws Exception {
        Fake f = new Fake(page("0", "on"), "");
        assertReason(f, ContentAccessException.Reason.LOGIN_REQUIRED);
        assertEquals(0, f.posts);
    }
    @Test public void verification_token_is_actionable_and_no_readback() throws Exception {
        Fake f = new Fake(page("42", "on"), ""); f.response = "auth";
        assertReason(f, ContentAccessException.Reason.AGE_VERIFICATION_REQUIRED);
        assertEquals(1, f.posts); assertEquals(1, f.gets);
    }
    @Test public void login_token_is_actionable() throws Exception {
        Fake f = new Fake(page("42", "on"), ""); f.response = "login";
        assertReason(f, ContentAccessException.Reason.LOGIN_REQUIRED);
    }
    @Test public void known_minor_does_not_submit_enabling_request() throws Exception {
        Fake f = new Fake(page("42", "on").replace("mem_adt:\"1\"", "mem_adt:\"0\""), "");
        assertReason(f, ContentAccessException.Reason.AGE_RESTRICTED);
        assertEquals(0, f.posts);
    }
    @Test public void ok_without_matching_readback_never_reports_success() throws Exception {
        for (String after : new String[]{page("42", "on"), ""}) {
            Fake f = new Fake(page("42", "on"), after);
            try { new AdultModeClient(f).setEnabled(true); fail(); }
            catch (IOException expected) { assertFalse(expected.getMessage().contains("42")); }
            assertEquals(1, f.posts);
        }
    }
    @Test public void lost_session_during_readback_requires_login() throws Exception {
        Fake f = new Fake(page("42", "on"), page("0", "on"));
        assertReason(f, ContentAccessException.Reason.LOGIN_REQUIRED);
        assertEquals(1, f.posts);
    }
    @Test public void unknown_result_does_not_echo_response_or_retry() throws Exception {
        Fake f = new Fake(page("42", "on"), ""); f.response = "private server response";
        try { new AdultModeClient(f).setEnabled(true); fail(); }
        catch (IOException expected) { assertFalse(expected.getMessage().contains("private")); }
        assertEquals(1, f.posts); assertEquals(1, f.gets);
    }
    @Test public void unknown_initial_state_does_not_post() throws Exception {
        Fake f = new Fake("", "");
        try { new AdultModeClient(f).setEnabled(true); fail(); }
        catch (IOException expected) { assertEquals(0, f.posts); }
    }
    private static void assertReason(Fake f, ContentAccessException.Reason reason) throws Exception {
        try { new AdultModeClient(f).setEnabled(true); fail(); }
        catch (ContentAccessException expected) { assertEquals(reason, expected.reason); }
    }
}
