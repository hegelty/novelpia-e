# Novelpia site adapter — observations and implementation

Scope: `app/src/main/java/me/crema/novelia/site/` (+ tests in
`app/src/test/java/me/crema/novelia/site/`). This document records only
endpoints and data shapes that were directly observed in Novelpia's public
HTML/JS on 2026-09-27, plus the adapter's behaviour and limitations.

No paywall/age-gate bypass is implemented. No ticket is consumed, nothing is
purchased, and no credentials are probed. The client reproduces only the
requests the public site makes for normal viewing flows.

## Hosts and URL policy

- Primary host: `https://novelpia.com`
- Also valid: `*.novelpia.com` (e.g. `book.novelpia.com` catalog pages).
- The adapter accepts **https only**, absolute URLs whose host is exactly
  `novelpia.com` or ends with `.novelpia.com`, plus root-relative paths.
- Look-alike hosts such as `novelpia.com.evil`, userinfo tricks
  (`https://novelpia.com@evil.example/`), non-https and other ports are
  rejected with `IllegalArgumentException` ("URL not allowed ...").
  Validation uses `java.net.URI`, never string prefix matching.
- Extracted links are absolutized with the same host check; foreign or
  malformed links are silently dropped, never fetched.

## Observed endpoints

| Endpoint | Method | Observed use | Params |
|---|---|---|---|
| `/proc/login` | POST | login form (`<form action="/proc/login" id="login_box">`) | `email`, `wd`, `redirectrurl` |
| `/proc/episode_list_viewer` | POST | viewer JS `episode_list_viewer(page)` | `novel_no`, `sort` (`localStorage['novel_sort_<no>']`, e.g. `up`), `page` |
| `/viewer/{content_no}` | GET | SSR viewer page (metadata + gallery) | — |
| `/proc/viewer_data/{content_no}` | POST | viewer JS `load_viewer_data()` | `size` (`'14'`), `viewer_paging` |
| `/proc/novel` | GET | search page Vue `get_search()` | `cmd=novel_search`, `search_type=all`, `search_val`, `page`, `rows`, `sort_col=last_viewdate`, `list_display`, plus filter keys; also `cmd=recommend_novel_list` on the /search landing |
| `/proc/login_google`, `/proc/login_apple`, `/proc/login_captcha` | — | social/captcha login entry points, not implemented | — |
| `/proc/episode_buy` | POST | purchase flow (`episode_buy(idx)`), **never called by this client** | `episode_no`, `csrf`, `option=viewer` |
| `/proc/free_ticket` | POST | ticket receive/use, **never called by this client** | `cmd`, `csrf`, `content_no`, ... |
| `/comic_episode/{id}` | — | comic viewer path, not implemented | — |

`/proc/policy`, `/proc/plus_payment_up`, `/proc/member_*` etc. were seen in
page JS but are outside this adapter's scope.

## Login (implemented)

`POST https://novelpia.com/proc/login` with the user's own credentials:

```
email=<email>&wd=<password>&redirectrurl=
```

- `AccountClient.login()` posts the explicit email/password form, then checks
  `/mybook/last_view` for the observed top-level member marker. A generic login
  string in HTML is not sufficient. Unknown/network states are not success.
- Optional password storage lives separately in `CredentialStore`; it is written
  only after authenticated success and explicit opt-in. AndroidKeyStore RSA-2048
  wraps a fresh 64-byte AES/HMAC key bundle. AES-256-CBC plus HMAC-SHA256 protects
  the bounded length-prefixed UTF-8 credential payload; MAC verification precedes
  decryption, and malformed/trailing envelope bytes are rejected. Neither key is
  stored in plaintext. No fallback to unencrypted preferences.
- Password whitespace is preserved. Email whitespace is trimmed. Keys and
  credentials are not included in exceptions/logs; app backup is disabled.
- Opt-in cold-start automatic login performs one attempt, not an unbounded retry
  loop. Authentication rejection disables automatic login; network failures do
  not delete the stored password. The user can turn auto-login off or delete the
  saved credentials. Logout clears both saved credentials and memory cookies.
- Actual Google session transfer remains unverified; saving email credentials
  does not implement Google OAuth or store a Google password/session.

## Authenticated shelf UI (2026-09-27)

Observed in the user's authenticated local browser, without exporting credentials:

- `/mybook/{like|alarm|collect|last_view}/{group}/{sort}/{page}`; the unnumbered
  tab aliases are also accepted. Group `0` is all, `-1` unclassified, `-2` hiatus;
  positive group IDs are accepted only through bounded canonical routes.
- Sort values: `date` (공개일자순), `view` (조회순), `list` (등록순), `vote` (추천순).
  The recent-reading shelf advertises no sort/group choices; do not fabricate them.
- Options are taken from `.mybook-filter-align-box` and `move_cate(...)` controls.
  Native controls issue new server GETs, not a local sort of one downloaded batch.
- `?search=` is the observed shelf search query. Search text is bounded to 100
  UTF-16 code units, encoded once, and preserved alongside shelf/group/sort across
  adjacent server pages. Unknown query names and foreign hosts are rejected.
- `.novel-btn-continue` text `EP.N 이어보기` supplies the last-read ordinal.
  `.novel-numerical`'s `회차` field supplies the total count, and `.writer-name`
  supplies author text. Viewer IDs are not episode ordinals. Missing fields stay
  unknown; total count is not asserted to be the latest episode's unique ID.
- The UI offers shelf tabs, sort/group/search, explicit continue/episode-list
  choice and screen-at-a-time pagination. No group creation/deletion, preferred
  novel mutation or history deletion is performed.
- Real HTML was tested transiently via `tools/LibraryProbe.java`; output contains
  bounded counts/booleans only. No HTML, account titles or session files were saved.

## Catalog browsing (implemented, HTML only)

`browse(url)`:

1. Strictly validates the URL.
2. GETs it and parses server-rendered HTML for links (no JS execution):
   - `a[href*='/novel/']` → `Entry(kind="novel")`
   - `a[href*='/viewer/']` → `Entry(kind="chapter")`
3. Capped: 100 novel rows / 200 chapter rows, de-duplicated by absolute URL,
   plain text only (no HTML injection).

Works on ssr catalog pages such as `https://novelpia.com/novel/{id}` and
`https://book.novelpia.com/webnovel/ranking`.

## Search (implemented via the site's own search, not the legacy URL)

**Observed:** the legacy `/search` page is a Vue app. Its results container is
`v-for="i in search_list"` and `get_search()` fetches:

```
GET /proc/novel?cmd=novel_search&search_type=all&search_val=<keyword>
    &page=1&rows=30&sort_col=last_viewdate&list_display=list
    &novel_type=&start_count_book=&end_count_book=&novel_age=&start_days=
    &novel_genre=&block_out=0&block_stop=0&is_contest=0
```

Response shape observed in the page code:

```json
{ "status": 200, "code": "...", "errmsg": "...", "total_cnt": N,
  "list": [ { "novel_no": 442723, "novel_name": "...", "writer_nick": "...",
              "novel_type": 1, "is_complete": 0, "count_book": 72,
              "novel_genre_arr": ["판타지", ...] }, ... ] }
```

`browse()` detects the MainActivity search URLs:

- `/search/all//1/{keyword}?...` (the URL the site's own search box builds)
- `/search?search_string={keyword}` (the legacy box used by MainActivity)

and runs the same `/proc/novel` request with `cmd=novel_search`. Rows become
`Entry(kind="novel", url=/novel/{novel_no})` with a `detail` string built from
observed fields (`novel_genre_arr`, `writer_nick`, `is_complete==1` → 완결,
`count_book` → N회차).

Query forwarding is allow-list only: `page`, `rows`, `sort_col`, `is_complete`
and the other observed filter keys from the caller's URL pass through, and
defaults (`page=1`, `rows=30`, `sort_col=last_viewdate`, ...) fill only what
the caller omitted. Private/unknown keys (`search_string` as a query key,
`search_val` from the URL, or arbitrary keys such as `evil`) are never
forwarded; the keyword is always sent once as the site's own `search_val`.

- Empty keyword → `IOException("검색 키워드가 없습니다.")`.
- `status != 200` or a malformed/empty body → informative `IOException`
  instead of a silently-empty list.

Note: without a live fixture we could not replay `/proc/novel` verbatim, so
the endpoint is implemented from the observed JS, and the adapter (and tests)
treat a server error as an error - never as "no results".

## Episode list (implemented; page is 0-based)

The viewer JS posts `localStorage['novel_page_<novel_no>']` as `page`; the
value 0 is the first sheet, so `episodes(novelId, 0)` posts `page=0`.

```
POST /proc/episode_list_viewer
novel_no=442723&sort=up&page=0
```

The response is server-rendered HTML with rows `tr[data-episode-no]`. Badges
observed in the markup: `.b_free` (무료), `.b_plus` (PLUS), `.b_comp` (완결),
`.b_mono` (단편), `.b_cont` (연재중). Each row → `Entry(kind="chapter",
url=/viewer/{content_no})`, capped at 200, de-duplicated.

Non-numeric novel ids or negative pages return an empty list.

## Reading a chapter (implemented: normal entitled loader, no bypass)

`readChapter(viewerUrl)` reproduces exactly the load flow of the public
viewer (observed inline in `/viewer/{content_no}` and the viewer JS):

1. `GET /viewer/{content_no}` for SSR metadata:
   - `<title>` → chapter title
   - `input#content_no` / `input[name=content_no]` → content number
   - `input[name=content_no_next]` / `input[name=content_no_pre]` → prev/next
     URLs (empty when absent)
2. `POST /proc/viewer_data/{content_no}` with `size=14`, `viewer_paging=`
   (the site posts `localStorage['viewer_paging']`, which is empty/off for
   single-page mode - the default).
3. Parse the JSON: `data.s[]` lines, each rendered as one text line by the
   site JS (`$.each(data.s, ...) value.text`).

The server enforces login and entitlement for this request exactly as for the
browser; this client adds nothing and performs no purchase. Captured on
2026-09-27, the viewer page served the line text directly in `data.s[].text`;
the AES path (`CryptoJSAesJson.decrypt(value.text, '//')`) was commented out
in the served HTML. So no decryption is needed or reproduced for today's
payloads.

**Error handling (never fake text):**

- non-JSON payload (login/purchase gate page) → `IOException` with a short
  server message or "로그인 또는 구매 필요"
- `status != 200` → `IOException("본문을 불러오지 못했습니다: ...")`
- empty `s[]` → `IOException("본문이 비어 있거나 열람 권한이 없습니다. ...")`
- malformed JSON → `IOException("본문 응답을 해석할 수 없습니다: ...")`
- the adapter never falls back to `doc.text()` of the SSR page as the body;
  SSR text ("소설 내용을 불러오고 있습니다" etc.) is never returned.
- a body over 300,000 characters throws `IOException` ("본문이 너무 커서
  표시할 수 없습니다 (300,000자 초과)") — it is never silently truncated
  (a truncation could split a UTF-16 surrogate pair).

If a future server payload requires the site's AES routine, the routine
(`/js/cryptojs-aes.min.js` + `cryptojs-aes-format.js`, `CryptoJSAesJson`
format with separator `'//'`) would need to be reproduced in the app - that
is "not implemented today", not "impossible", and it is only legitimate
together with the unchanged server-side entitlement checks above.

## Chapter navigation

`Chapter.nextUrl` / `Chapter.previousUrl` come from
`input[name=content_no_next]` / `input[name=content_no_pre]` on the SSR
viewer page; empty string when absent. `Chapter.url` is the viewer URL that
was read.

## MainActivity contract

- `new SiteClient(http)` with `me.crema.novelia.net.NativeHttp`
  (`ctor(Context)`, `get(String)`, `post(String, Map<String,String>)`,
  `clearCookies()`); `NativeHttp` is final, so tests use the package-private
  `SiteClient.HttpAgent` seam.
- `List<Entry> browse(String url)` — includes the translated search flow.
- `List<Entry> episodes(String novelId, int page)` — `page=0` first sheet.
- `LoginResult login(String email, String password)`.
- `Chapter readChapter(String viewerUrl)`.
- `Entry` fields: `title`, `url`, `kind`, `detail` (all plain text).
- `Chapter(title, text, url, nextUrl, previousUrl)`.
- `LoginResult.success`, `LoginResult.message`.

## Boundaries / not implemented

- Comic viewer (`/comic_episode/...`, `/proc/viewer_data` for comics not
  verified), event/purchase/ticket endpoints, social login, captcha login.
- `/proc/novel` has not been live-verified; the server response is treated as
  authoritative and errors surface rather than fabricating results.
- No JS execution: catalog extraction is server-rendered HTML only; search
  uses the site's own AJAX endpoint with the observed parameters.
- No purchases, no ticket consumption, no auth bypass, no credential
  probing.

## Observed free public episode (for instrumented read test)

From the saved page-0 episode sheet (`np_eplist_p0.html`) the first row was
badge `.b_free` (무료): `https://novelpia.com/viewer/5890523`. Its served
viewer page (`np_viewer5890523.html`) is the one whose loader
(`POST /proc/viewer_data/5890523`, `size=14`, `viewer_paging=`) was captured.
All rows in the other saved sheet (`np_eplist.html`) were `.b_plus` (PLUS), so
no purchase data was consulted.
# 회차 번호·등록 편수·다음 회차 보완 (2026-09-27)

- 실제 로그인된 `/mybook`에서 마지막 읽은 `EP.N`과 `회차` 편수가 같은데도
  `.novel-btn-next`가 있는 행을 확인했다. 동일 편수에서 버튼이 없는 행도 있어,
  숫자 비교로 다음 회차 존재·완독·최신 회차 번호를 판정하면 안 된다.
- 해당 버튼의 관찰된 핸들러는 `get_next_episode(novel_no, novel_epi_no)`다.
  두 번째 값은 사이트의 순서 키로 보존하며 뷰어 ID·등록 편수에서 계산하지 않는다.
  JavaScript를 실행하지 않고 제한된 함수명/숫자 인자 패턴만 파싱한다.
- 사용자 클릭 시 `POST /proc/mybook`에 `mode=get_next_episode`, `novel_no`,
  `novel_epi_no`를 전송한다. status 200의 `result.next_episode_no`만 뷰어
  주소로 사용한다. `wait_episode=1`은 공개 대기, `end_episode=1`은 마지막
  회차 안내로 처리한다. 사이트의 `set_init_next_episode` 변경 요청은 호출하지 않는다.
- 실제 동일 편수 사례를 브라우저에서 확인한 결과 status 200, 유효 다음 ID 있음,
  공개 대기 아님, 마지막 아님이었다. 반환된 본문은 열지 않았고
  작품명·식별자·쿠키는 출력하거나 파일에 기록하지 않았다.
- 공개 무료 뷰어에서 `#novel_no`, `#content_no_next`가 name 없는 ID 입력인
  것을 확인했다. 이를 읽고 안전한 자동이동 URL도 지원한다. 동일 회차·0·외부
  호스트·쿼리/프래그먼트·javascript URL은 다음/이전 링크로 채택하지 않는다.
- `.menu-title-wrapper > .menu-top-title` 및 `.menu-top-tag`를 회차 제목·
  라벨로 사용한다. 문서 제목 맨 앞의 사이트명/홍보 접두사만 제거한다.
  본문·권한 검사 흐름은 변경하지 않았다.
