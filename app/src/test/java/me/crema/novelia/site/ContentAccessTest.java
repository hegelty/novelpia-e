package me.crema.novelia.site;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class ContentAccessTest {
    private static final String VIEWER = "<input id='content_no' value='4390049'>";
    private static final String BODY = "{\"status\":200,\"s\":[{\"text\":\"본문\"}]}";

    // Reduced from the anonymous official /viewer/4390049 response,
    // linked by /novel/343176, captured on 2026-10-03. No chapter text.
    private static final String LOGIN_GATE =
            "<div id='alert_modal' class='modal fade' style='display:none;'>"
            + "<div class='modal-body pd-20'><p class='mg-b-5'>로그인이 필요합니다.</p></div>"
            + "<button onclick=\"location = '/?login_req=2';\">확인</button></div>"
            + "<script>try { $('#alert_modal').modal('show');"
            + "$('#alert_modal').on('hidden.bs.modal', function () { location = '/?login_req=2'; });"
            + "} catch (e) { alert('로그인이 필요합니다.'); location = '/?login_req=2'; }</script>";

    private static final class FakeHttp implements SiteClient.HttpAgent {
        String html = VIEWER;
        String body = BODY;
        int posts;

        @Override public String get(String url) { return html; }
        @Override public String post(String url, Map<String, String> form) {
            posts++;
            return body;
        }
    }

    @Test public void observedViewerLoginGateStopsBeforeRequestingBody() throws Exception {
        FakeHttp http = new FakeHttp();
        http.html = LOGIN_GATE;
        assertGate(new SiteClient(http), ContentAccessException.Reason.LOGIN_REQUIRED);
        assertEquals(0, http.posts);
    }

    @Test public void observedAuthenticatedAdultOffGateStopsBeforeRequestingBody() throws Exception {
        // Observed on /viewer/4390049 with an authenticated, adult-mode-off
        // session on 2026-10-03. Retain only the fixed gate, never account data.
        FakeHttp http = new FakeHttp();
        http.html = "<div id='alert_modal'><div class='modal-body'>"
                + "성인모드가 꺼져있는 상태입니다.</div></div>"
                + "<script>\r\n\ttry { $('#alert_modal').modal('show');"
                + "$('#alert_modal').on('hidden.bs.modal', function () { history.go(-1); });"
                + "} catch (e) { alert('성인모드가 꺼져있는 상태입니다.'); history.go(-1); }</script>";
        assertGate(new SiteClient(http), ContentAccessException.Reason.ADULT_MODE_REQUIRED);
        assertEquals(0, http.posts);
    }

    @Test public void catalogAndEpisodeGateAreNotEmptySuccesses() throws Exception {
        FakeHttp http = new FakeHttp();
        http.html = LOGIN_GATE;
        http.body = LOGIN_GATE;
        SiteClient client = new SiteClient(http);
        try {
            client.browse("/novel/343176");
            fail("Expected login gate");
        } catch (ContentAccessException e) {
            assertEquals(ContentAccessException.Reason.LOGIN_REQUIRED, e.reason);
        }
        try {
            client.episodes("343176", 0);
            fail("Expected login gate");
        } catch (ContentAccessException e) {
            assertEquals(ContentAccessException.Reason.LOGIN_REQUIRED, e.reason);
        }
    }

    @Test public void sharedScriptsHiddenModalsAndChapterWordsAreNotGates() throws Exception {
        FakeHttp http = new FakeHttp();
        http.html = VIEWER
                + "<script>function adt_mode() { alert('성인 모드를 켜주세요.');"
                + "confirm('성인/본인인증이 필요합니다.'); alert('로그인이 필요합니다.'); }</script>"
                + "<script>function login() { $('#alert_modal').modal('show'); }</script>"
                + "<div id='alert_modal' style='display:none'><div class='modal-body'>로그인이 필요합니다.</div></div>";
        http.body = "{\"status\":200,\"errmsg\":\"로그인이 필요합니다.\","
                + "\"s\":[{\"text\":\"성인 모드를 켜주세요.\"}]}";
        assertEquals("성인 모드를 켜주세요.", new SiteClient(http).readChapter("/viewer/4390049").text);
        assertEquals(1, http.posts);
    }

    @Test public void explicitJsonErrorMessagesKeepSeparateActionableReasons() throws Exception {
        String[] messages = {"로그인이 필요합니다.", "성인 모드를 켜주세요.",
                "성인/본인인증이 필요합니다.", "청소년은 성인 작품을 이용하실 수 없습니다."};
        ContentAccessException.Reason[] reasons = {
                ContentAccessException.Reason.LOGIN_REQUIRED,
                ContentAccessException.Reason.ADULT_MODE_REQUIRED,
                ContentAccessException.Reason.AGE_VERIFICATION_REQUIRED,
                ContentAccessException.Reason.AGE_RESTRICTED};
        for (int i = 0; i < messages.length; i++) {
            FakeHttp http = new FakeHttp();
            http.body = "{\"status\":403,\"errmsg\":\"" + messages[i] + "\"}";
            assertGate(new SiteClient(http), reasons[i]);
        }
    }

    @Test public void bodyEndpointHtmlGateIsRecognized() throws Exception {
        FakeHttp http = new FakeHttp();
        http.body = LOGIN_GATE;
        assertGate(new SiteClient(http), ContentAccessException.Reason.LOGIN_REQUIRED);
    }

    @Test public void unknownFailureDoesNotInventAnAdultOrLoginRequirement() throws Exception {
        FakeHttp http = new FakeHttp();
        http.body = "{\"status\":403,\"errmsg\":\"회차를 구매해주세요.\"}";
        try {
            new SiteClient(http).readChapter("/viewer/4390049");
            fail("Expected error");
        } catch (IOException e) {
            assertFalse(e instanceof ContentAccessException);
            assertEquals("본문을 불러오지 못했습니다: 회차를 구매해주세요.", e.getMessage());
        }
    }

    private static void assertGate(SiteClient client, ContentAccessException.Reason reason)
            throws Exception {
        try {
            client.readChapter("/viewer/4390049");
            fail("Expected " + reason);
        } catch (ContentAccessException e) {
            assertEquals(reason, e.reason);
        }
    }
}
