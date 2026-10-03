package me.crema.novelia.site;

import java.io.IOException;
import java.util.Map;

/** Entirely synthetic network seam: no real account, novels or chapter requests. */
public final class NavigationFixture {
    public int requestedPage = -1;
    public final SiteClient client = new SiteClient(new SiteClient.HttpAgent() {
        @Override public String get(String url) throws IOException {
            if (!url.matches("https://novelpia.com/viewer/10[12]")) throw new IOException("Unexpected fixture request");
            return "<title>노벨피아 - 웹소설로 꿈꾸는 세상 ! - 합성 작품</title>"
                    + "<input id='novel_no' value='999'><input id='content_no' value='"
                    + (url.endsWith("101") ? "101" : "102") + "'>"
                    + "<input id='content_no_next' value='103'>"
                    + "<div class='menu-title-wrapper'><span class='menu-top-tag'>EP.30</span>"
                    + "<div class='menu-top-title'>목록 복귀 확인</div></div>";
        }
        @Override public String post(String url, Map<String, String> form) throws IOException {
            if (url.endsWith("/proc/episode_list_viewer")) {
                requestedPage = Integer.parseInt(form.get("page"));
                StringBuilder html = new StringBuilder("<table id='episode_table'>");
                for (int i = 0; i < 30; i++) html.append("<tr class='ep_style5' data-episode-no='")
                        .append(101 + i).append("'><td><b>합성 회차 ").append(i)
                        .append("</b></td></tr>");
                return html.append("</table>").toString();
            }
            if (url.matches("https://novelpia.com/proc/viewer_data/10[12]"))
                return "{\"status\":200,\"s\":[{\"text\":\"합성 본문입니다. 목록 복귀를 확인합니다.\",\"size\":11,\"align\":\"left\"}]}";
            throw new IOException("Unexpected fixture request");
        }
    });
}
