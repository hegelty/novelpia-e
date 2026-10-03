package me.crema.novelia.account;

import org.junit.Test;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;

public class LatestEpisodeClientTest {
    private static final String URL = "https://novelpia.com/novel/442723";
    private static String row(String id, String label) {
        return "<tr data-episode-no='" + id + "'><td><b>제목 EP.999</b>"
                + "<div class='ep_style2'><font><span>" + label + "</span>"
                + "<span><i></i>12345</span></font></div></td></tr>";
    }
    private static String sheet(String rows) { return "<table id='episode_table'>" + rows + "</table>"; }
    private static final class Fake implements LatestEpisodeClient.HttpAgent, LatestEpisodeClient.Clock {
        long time;
        int calls;
        String body = sheet(row("987654", "EP.79") + row("123456", "EP.78"));
        String endpoint;
        Map<String, String> form;
        boolean fail;
        Runnable onPost;
        @Override public long now() { return time; }
        @Override public String post(String url, Map<String, String> values) throws IOException {
            calls++; endpoint = url; form = new LinkedHashMap<String, String>(values);
            if (onPost != null) onPost.run();
            if (fail) throw new IOException("offline");
            return body;
        }
    }
    @Test public void actualLabelComesFromMetadataNotTitleCountOrViewerId() {
        String html = "<div>등록 81편 EP.999</div>" + sheet(row("999999", "EP.079") + row("999998", "EP.78"));
        assertEquals("EP.079", LatestEpisodeClient.parseLatestLabel(html));
    }
    @Test public void ascendingOrUnknownMetadataDoesNotClaimLatest() {
        assertEquals("", LatestEpisodeClient.parseLatestLabel(sheet(row("1", "EP.0") + row("2", "EP.1"))));
        assertEquals("", LatestEpisodeClient.parseLatestLabel(sheet(row("1", "공지") + row("2", "EP.79"))));
        assertEquals("", LatestEpisodeClient.parseLatestLabel("<h1>로그인이 필요합니다 EP.79</h1>"));
        assertEquals("", LatestEpisodeClient.parseLatestLabel(sheet(row("bad", "EP.79"))));
        assertEquals("", LatestEpisodeClient.parseLatestLabel(sheet(row("1", "EP.79")) + sheet(row("2", "EP.78"))));
    }
    @Test public void prologueZeroIsAnActualLabel() {
        assertEquals("EP.0", LatestEpisodeClient.parseLatestLabel(sheet(row("123", "EP.0"))));
    }
    @Test public void requestUsesObservedNewestFirstRouteAndCachesUntilExpiry() {
        Fake fake = new Fake(); LatestEpisodeClient client = new LatestEpisodeClient(fake, fake);
        assertEquals("EP.79", client.latestLabel(URL));
        assertEquals("https://novelpia.com/proc/episode_list_viewer", fake.endpoint);
        assertEquals("442723", fake.form.get("novel_no"));
        assertEquals("UP", fake.form.get("sort")); assertEquals("0", fake.form.get("page"));
        fake.body = sheet(row("888888", "EP.80")); fake.time = 119999;
        assertEquals("EP.79", client.latestLabel(URL)); assertEquals(1, fake.calls);
        fake.time = 120000;
        assertEquals("EP.80", client.latestLabel(URL)); assertEquals(2, fake.calls);
        client.clear(); client.latestLabel(URL); assertEquals(3, fake.calls);
    }
    @Test public void invalidUrlsDoNotSendRequestsAndErrorsAreBrieflyCached() {
        Fake fake = new Fake(); LatestEpisodeClient client = new LatestEpisodeClient(fake, fake);
        assertEquals("", client.latestLabel("https://novelpia.com.evil/novel/442723"));
        assertEquals("", client.latestLabel(URL + "?sort=DOWN")); assertEquals(0, fake.calls);
        fake.fail = true; assertEquals("", client.latestLabel(URL)); assertEquals(1, fake.calls);
        fake.fail = false; fake.time = 29999;
        assertEquals("", client.latestLabel(URL)); assertEquals(1, fake.calls);
        fake.time = 30000;
        assertEquals("EP.79", client.latestLabel(URL)); assertEquals(2, fake.calls);
    }
    @Test public void cacheBoundDoesNotAccumulateEveryVisitedNovel() {
        Fake fake = new Fake(); LatestEpisodeClient client = new LatestEpisodeClient(fake, fake);
        for (int i = 1; i <= 129; i++) client.latestLabel("https://novelpia.com/novel/" + i);
        client.latestLabel("https://novelpia.com/novel/1"); assertEquals(130, fake.calls);
    }
    @Test public void clearingDuringARequestDoesNotRepopulateTheCache() {
        Fake fake = new Fake(); final LatestEpisodeClient client = new LatestEpisodeClient(fake, fake);
        fake.onPost = new Runnable() { @Override public void run() { client.clear(); } };
        assertEquals("EP.79", client.latestLabel(URL));
        fake.onPost = null;
        fake.body = sheet(row("888888", "EP.80"));
        assertEquals("EP.80", client.latestLabel(URL));
        assertEquals(2, fake.calls);
    }
    @Test public void immutableItemKeepsCountUntilActualLabelArrives() {
        me.crema.novelia.site.SiteClient.Entry entry = new me.crema.novelia.site.SiteClient.Entry("작품", URL, "novel", "");
        LibraryPage.Item item = new LibraryPage.Item(entry, "https://novelpia.com/viewer/100", 70, 83, "작가", "101");
        LibraryPage.Item enriched = item.withLatestEpisodeLabel("EP.079");
        assertEquals("다음 회차 있음 · 마지막 읽은 EP.70 · 등록 83편", item.progressLabel());
        assertEquals("다음 회차 있음 · 마지막 읽은 EP.70 · 최신 EP.079", enriched.progressLabel());
        assertEquals(83, enriched.totalEpisodes);
        assertEquals(item.continueUrl, enriched.continueUrl);
        assertEquals(item.author, enriched.author);
        assertEquals(item.nextEpisodeKey, enriched.nextEpisodeKey);
        assertEquals(item.progressLabel(), item.withLatestEpisodeLabel("83편").progressLabel());
    }

}
