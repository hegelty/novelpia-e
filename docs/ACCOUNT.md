# Account and library adapter

`app/src/main/java/me/crema/novelia/account/AccountClient.java` is a small
account adapter for normal, user-initiated use. It keeps no credentials,
member profile, or library data after returning results. Session cookies are
held only by the injected `NativeHttp` instance, which uses its in-memory
cookie manager.

Google-account users can import an explicitly exported PC Novelpia session
through `MainActivity`'s document picker. See `GOOGLE_LOGIN.md`. Import replaces
the memory jar only after bounded validation, then verifies the server marker;
unverified imported sessions are cleared. No Google credentials are collected.
This workflow has synthetic parser/CookieManager tests. The user tried real
Google-account session imports but the app still reported login required;
the transfer failure has not been resolved.

User-run observation on September 27, 2026: after email login the in-app
account check displayed server-confirmed authentication, but the favorites
query was reported as unverified. This confirms the email-session status path,
not successful library retrieval. The previous aggregate check collapsed all
favorites IO errors into one message. Direct emulator UI interaction then
confirmed the specific error from the non-200 application-status branch;
the numeric status itself was not exposed by that build.
No credential or authenticated response body was collected for this observation.

The updated adapter emits only generated `favorites:<category>[:numeric-code]`
diagnostics. Server `errmsg`, response bodies, titles and exception causes are
never incorporated. Transport HTTP status and JSON application status are
separate categories. A malformed nonempty list fails rather than becoming a
false empty library. Explicit status 200 with absent/null items follows the
observed frontend's `items || []` behavior.

The exact read-only `/proc/mybook` call now uses `NativeHttp.postAjax`, adding
same-origin `Origin`, `Referer`, `X-Requested-With` and JSON `Accept` headers
before sending the body. Ordinary email-login requests are unchanged.
This aligns request format; it is not proof that missing AJAX headers caused
the user's failure. After re-login on this build, direct in-app UI automation
reported `login=authenticated, favorites_count=0`. The user confirmed that their
actual favorites count is zero. Thus email authentication and a real empty
favorite-list response are verified; populated favorites are not thereby verified.
Later authenticated browser inspection established the recent/preferred HTML
contract, implemented below. That is distinct from native email-login validation.

## Public-page observations (September 27, 2026)

- The public bottom navigation points to `/mybook/last_view` for recent
  reading. A credential-free GET of that page while signed out returned a
  shell whose body sets `_top_obj.data.mem_no` to `"0"` and redirects to
  `/?login_req=1`; it provides no recent rows in that state. A credential-free
  GET of `/mybook` also redirects there and reveals no complete-shelf contract.
- Session detection examines only the explicit `const/let/var _top_obj = { data: {
  ... mem_no: "N"` script literal. A positive number is authenticated, zero
  is unauthenticated; a missing marker is unknown unless the exact inline
  signed-out redirect is present. Shared `#login_box`, generic `login_req`
  text, and unrelated `mem_no` fields do not determine status. The number is
  never exposed through `SessionStatus`.
- The `/mybook/last_view` public capture was signed out and therefore does
  **not** reveal its authenticated row markup or data request. The homepage's
  "최근 본 작품" component reads browser `localStorage` (`userLastNovelData`),
  which is a different feature and is not used here as account history.
  That anonymous-only investigation was insufficient. The later authorized
  inspection of the user's signed-in browser established the scoped HTML
  contract described below; unrelated viewer links and localStorage are still
  never treated as account history.
- The public homepage's "나의 최애 작품" component issues
  `POST /proc/mybook` with `{mode: "favorite_list"}` and consumes
  `{status, items}`; each observed item has `novel_no`, `novel_name`, and
  `cover`. This is the component's "최애 작품" data; its UI renders exactly
  five slots. The adapter only sends that list mode and returns at most the
  first five items; it never sends the separate `favorite_set` mutation. This
  is distinct from, and is **not** represented as, the complete `/mybook`
  library or the full "선호작" shelf.
- The email form is `POST /proc/login` with `email`, `wd`, and
  `redirectrurl`. Login success is decided only by fetching the recent page
  using the same cookie session and validating its explicit member marker.
  Generic response text is not considered proof of authentication.

No credentials were entered or read by the inspection tools. The client does not
call logout, favorites mutation, ticket, purchase, or payment endpoints. It
does not bypass access controls; the server's normal authenticated-page guard
continues to apply.

## API and limitations

`sessionStatus()` returns `SessionStatus` with public enum
`SessionStatus.State` (`AUTHENTICATED`, `UNAUTHENTICATED`, or `UNKNOWN`),
`state`, `message`, and `isAuthenticated()`. `login(email, password)` returns
the same shape; callers should retain no credential after invoking it.
`library(url)` validates a narrow read-only route, fetches one HTML page, checks
the authentication marker on that exact response, and returns `LibraryPage`.
`recent()` is a convenience returning only the first recent-history page's
novel entries; the native UI uses `library()` for pagination and continuation.
`favorites()` returns up to five novel `SiteClient.Entry` items from the
homepage's five-slot "최애 작품" component endpoint, not the full "선호작"
shelf.

## Authenticated SSR contract (September 27, 2026)

The user signed in manually in a dedicated local Chromium profile and authorized
read-only inspection. No cookie values, credentials, member IDs or titles were
printed or saved as fixtures.

- Preferred shelf: `/mybook`; numbered pages `/mybook/like/0/date/N`.
- Recent account history: `/mybook/last_view`; numbered pages
  `/mybook/last_view/0/date/N`.
- Pages are 1-based. The observed first pages each contain 30 rows. The app does
  not crawl the whole account; it keeps one page and follows only observed
  adjacent same-shelf links in `.pagination`.
- Rows are direct `.novel-list-real-container` children of the unique
  `.mybook-data-list-items`. The `.novel-name` title element has a literal
  `location.href='/novel/NUMBER';` handler.
- Optional `.novel-btn-continue` contains the same literal assignment to
  `/viewer/NUMBER`. The parser extracts this restricted URL without executing
  JavaScript. Reading requires a separate explicit user selection. Mutation
  buttons and `get_next_episode(...)` are ignored.
- An empty search result established the exact empty marker
  `등록된 작품이 없습니다.` inside the list container. Missing/malformed markup
  is not assumed to mean an empty shelf.
- Only the default `0/date` route is implemented. Custom groups, filters,
  sorting, history deletion and preferred-list edits are not implemented.

`LibraryParser` bounds input, row count, title length and page number. Parser
errors are generated fixed text, not server data. Synthetic unit tests contain
no actual account data. `tools/LibraryProbe.java` accepts length-framed HTML on
stdin and prints only counts/booleans, allowing live parser checks without
writing authenticated HTML to disk or restarting the signed-in app. For old
adbd without raw stdin it also supports an explicitly launched, bounded
loopback-only socket mode (`--listen PORT COUNT`, maximum 10 requests).
This helper is not packaged into the app and never accesses app cookies.

## Native signed-in UI verification (September 27, 2026)

After updating the APK, the user manually signed into the native app by email.
Direct UI checks (without restarting, installing, instrumentation or attaching a
debugger) confirmed server authentication and successful first-page loads for
both shelves. The recent shelf was navigated through all 7 pages (182 rows);
the preferred shelf through all 3 pages (70 rows). Previous-page navigation,
refresh, first/last paging-button states and the genuine empty favorites UI
also passed. These are observations for this account at test time, not fixed
page counts or promises for other accounts.

A recent row's continuation choice dialog was opened and then cancelled.
Neither the continuation nor episode-list action was selected: no account
chapter was opened and no purchase was attempted. Only fixed control labels,
bounded counts and success flags were emitted; UI XML and real titles stayed
in memory. This verifies native library loading, not paid-chapter access or
the still-unresolved Google-session import.
