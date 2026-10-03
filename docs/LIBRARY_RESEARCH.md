# Novelpia library request-contract research

**Scope:** anonymous, read-only inspection of Novelpia's public HTML and the exact shared script URL named by that HTML. No credentials, authenticated state, or endpoint submissions were used. This report is limited to what the inspected sources establish.

## Findings

### Recent history: `/mybook/last_view`

- The anonymous homepage capture at `/tmp/novelpia-home.html` sends the ordinary novel bottom-nav “최근기록” destination to `/mybook/last_view?sid=bottomnav4` (lines 5657–5663).
- An anonymous GET of `https://novelpia.com/mybook/last_view` returned an HTML page with that path in its Open Graph URL (captured at `/tmp/novelpia-public-check/1.txt`, line 44). Its inline page code stores a redirect cookie and then navigates to `/?login_req=1` (lines 1747–1750).
- The returned page therefore exposes a signed-out guard, not the authenticated recent-history view. It reveals **no history list request, mode, parameters, pagination contract, or row schema**.
- A sort field named `last_viewdate` occurs in unrelated search URL construction in the public shell (anonymous page line 1689; also homepage line 1563 in the supplied capture). This is search sorting and is **not evidence** for a recent-history request or response.

### Full/preferred shelf: `/mybook`

- An anonymous GET of `https://novelpia.com/mybook` returned a page titled as the library, but the shared output did not provide authenticated shelf rows or an endpoint contract. The response is captured at `/tmp/novelpia-public-check/2.txt`.
- The only directly observed `/proc/mybook` request is the favorite-novels helper embedded in the shared public shell. In `/tmp/novelpia-public-check/1.txt` (also present in `/tmp/novelpia-home.html`):
  - Lines 928–937: `$.ajax` posts to `/proc/mybook`; successful responses are read from `result.status`, `result.errmsg`, and `result.items`.
  - Lines 947–955: its read operation submits `{ mode: 'favorite_list' }`.
  - Lines 956–965: a separate `favorite_set` operation submits `novel_no` and `enabled` (`'1'`/`'0'`). This mutation path was **not invoked**.
  - Lines 939–944 and 980–984: favorite UI reads `item.novel_no`, `item.novel_name`, `item.cover`, and `item.restricted`.
- This is evidence for the **favorite-list** helper's client-side request and consumed fields only. It does not establish that `/proc/mybook` has a full preferred-shelf/history mode, or that any additional fields, ordering, paging, or row schema exist.

### Public script trace

- Both anonymous library HTML responses name `/js/novelpia.js?v=1787206696` (for example `/tmp/novelpia-public-check/1.txt`, line 62). That exact URL was fetched as an anonymous GET to `/tmp/novelpia-public-check/0.txt`.
- Searching that returned script for `mybook`, `last_view`, `recent`, `favorite`, and `/proc/` found no history or favorite-list implementation. It contains unrelated search, banner, and other shared procedures. No additional library-specific script URL was exposed by the signed-out route response.
- The favorite-list helper above is inline HTML JavaScript, rather than code discovered in that external shared script.

## Initial conclusion from anonymous pages

The observed public evidence is insufficient to implement `AccountClient.recent()` or a full preferred-shelf fetch faithfully. Keep recent history unsupported unless an authorized authenticated-source inspection becomes available. Do not infer account history from recommendations, search sorting, local storage, or the favorite helper. No guessed `/proc/mybook` modes or parameters should be tried based on this evidence.

## Follow-up: authorized signed-in browser inspection (2026-09-27)

The user subsequently signed into a separate local Chromium profile and authorized
inspection. This resolves the earlier contract blocker, without guessing AJAX modes:
both lists are server-rendered HTML. See `ACCOUNT.md` for the implemented selectors,
page routes and empty marker.

Observed page navigation: `/mybook/like/0/date/N` for the preferred shelf and
`/mybook/last_view/0/date/N` for recent history, with 1-based active pagination.
The actual linked second pages were fetched read-only and each returned 30 scoped
rows and active page 2. Empty preferred-search results were inspected via the site's
observed `?search=` parameter solely to establish the empty marker; search/filter UI
is not part of the new library implementation.

Only schema, read-only route constants, numeric counts and sanitized structure were
emitted by the inspection. No real titles, member identifiers, cookies, credentials,
or authenticated HTML fixture files are included in this repository.

## Evidence locations

- Existing anonymous homepage capture: `/tmp/novelpia-home.html`
- Anonymous GET `/mybook/last_view`: `/tmp/novelpia-public-check/1.txt`
- Anonymous GET `/mybook`: `/tmp/novelpia-public-check/2.txt`
- Exact shared script linked by those pages: `/tmp/novelpia-public-check/0.txt`

These temporary captures are research artifacts, not repository files. The page and script contents may change; the references above identify the inspected captures and their line numbers, not a guarantee of a stable site contract.
