# TLS design for the Android networking layer

Scope: `app/src/main/java/me/crema/novelia/net/` and
`app/src/main/assets/certs/`. This document records the TLS/trust model, the
anchor policy, the verified `novelpia.com` certificate chain used to select the
bundled root, and the official sources for it.

## 1. Goals

- Talk to **`novelpia.com` and its subdomains only**, over **HTTPS only**.
- Verify TLS the way a browser would: system trust **plus** an optional,
  documented pin to Amazon's public root — never “trust all”.
- Support Android **API 19** with modern TLS via **Conscrypt 2.5.2** without
  mutating global TLS defaults.
- Keep credentials, tokens, cookies and bodies out of logs, and bound memory.

## 2. TLS engine and protocol

- The parent build pins `org.conscrypt:conscrypt-android:2.5.2`. Its
  minimum Android API is 19, which matches this app's floor.
- `NativeHttp` builds its **own** isolated `SSLContext`:
  `SSLContext.getInstance("TLS", Conscrypt.newProvider())`. The context is
  initialized with only this layer's `X509TrustManager` and never installed as
  the process default, so no global TLS state is changed.
- API 19's platform TLS defaults do not enable TLS 1.2; Conscrypt 2.5.2
  supports it, so servers requiring it (including `novelpia.com`) work inside
  this private context.
- Every `HttpsURLConnection` receives this private `SSLSocketFactory`, and
  hostname verification is performed by the **default
  `HttpsURLConnection.getDefaultHostnameVerifier()`** — no custom hostname
  logic is used.

## 3. Trust model

Two layers, both mandatory:

1. **System trust store** (`TrustManagerFactory` default algorithm, initialized
   with the platform default keystore) — the same CA set the platform HTTPS
   stack uses. It is authoritative and consulted first.
2. **Bundled Amazon Root CA 1** (`assets/certs/AmazonRootCA1.pem`) as the only
   additional trust anchor. It is loaded into its **own** `KeyStore` and
   validated by the **standard PKIX provider**
   (`TrustManagerFactory` + `checkServerTrusted`), so certificate expiry,
   signatures and path constraints are enforced by the platform exactly as for
   the system store. There is **no hand-rolled path verification** anywhere in
   this layer, and **no intermediate certificate is bundled as a trust
   anchor**.

The custom `X509TrustManager` consults the system store first; only when it
rejects a chain is the anchor-only PKIX trust manager consulted, and that
manager can only accept a chain terminating at the bundled Amazon root. There
is no path that returns true without real PKIX validation, and the system
store is never weakened or replaced.

Hostname and origin hardening are separate layers (`HttpsURLConnection`
hostname verifier + the `novelpia.com` allow-list in `UrlPolicy`).

## 4. Verified novelpia.com certificate chain

Observed `2026-09-27` with `openssl s_client -showcerts` (connect
`novelpia.com:443`, SNI `novelpia.com`; system verify result `OK`):

| Position | Subject | Issuer | Serial | Valid from | Valid to |
| --- | --- | --- | --- | --- | --- |
| 0 (leaf) | `CN=novelpia.com` | `C=US, O=Amazon, CN=Amazon RSA 2048 M01` | `0C:CA:63:90:D5:4D:85:E7:21:CF:F2:C0:20:D6:0D:97` (long form) | 2026-02-14 | 2027-03-14 |
| 1 | `C=US, O=Amazon, CN=Amazon RSA 2048 M01` | `C=US, O=Amazon, CN=Amazon Root CA 1` | `07:73:12:38:0B:9D:66:88:A3:3B:1E:D9:BF:9C:CD:A6:8E:0E:0F` (long form) | 2022-08-23 | 2030-08-23 |
| 2 | `C=US, O=Amazon, CN=Amazon Root CA 1` | `C=US, ST=Arizona, O=Starfield Technologies, CN=Starfield Services Root Certificate Authority - G2` | — | 2015-05-25 | 2037-12-31 |

- Leaf SAN: `DNS:novelpia.com`, `DNS:*.novelpia.com` (observed, 2026-09-27).
- Leaf AIA: `CA Issuers - http://crt.r2m01.amazontrust.com/r2m01.cer`.
- The leaf signs with `sha256WithRSAEncryption` over an RSA 2048 key; the
  negotiated protocol was TLSv1.3 with `TLS_AES_128_GCM_SHA256` (the server
  also negotiates TLSv1.2, which is what API 19 + Conscrypt 2.5.2 provides).

### Root ambiguity and what we bundle

`Amazon Root CA 1` exists in two public forms with different fingerprints:

- **Modern self-signed root** `CN=Amazon Root CA 1` (2015-05-26 → +23y), the
  one published as `AmazonRootCA1.pem` in the current Amazon Trust Services
  repository. SHA-256(DER):
  `8E:CD:E6:88:4F:3D:87:B1:12:5B:A3:1A:C3:FC:B1:3D:70:16:DE:7F:57:CC:90:4F:E1:CB:97:C6:AE:98:19:6E`.
  This is the root the **intermediate** (`Amazon RSA 2048 M01`) is actually
  issued by.
- **Cross-signed root** (issued by Starfield Services Root CA - G2), the one
  `novelpia.com` serves as the last chain element. SHA-256(DER):
  `87:DC:D4:DC:74:64:0A:32:2C:D2:05:55:25:06:D1:BE:64:F1:25:96:25:80:96:54:49:86:B4:85:0B:C7:27:06`.

The bundled `AmazonRootCA1.pem` is the **modern, self-signed root**
(fingerprint `8E:CD:E6:…`), which is the secure trust anchor for this chain.
Server chains that terminate at an intermediate are transparently completed by
PKIX path building against this anchor; **the intermediate itself is not
bundled as a trust anchor** and cannot authorize anything by itself.

### Verification performed for this document

- `openssl verify -CAfile AmazonRootCA1.pem -untrusted AmazonRSA2048M01.pem <novelpia leaf>` → OK.
- A root-only `TrustManagerFactory` initialized with the bundled
  `AmazonRootCA1.pem` (no intermediate anchor) accepts the exact three-element
  chain served by `novelpia.com` (leaf → `Amazon RSA 2048 M01` → cross-signed
  root) as well as the two-element leaf → intermediate chain, verified directly
  with the PKIX provider (2026-09-27).
- The intermediate fetched from
  `https://crt.r2m01.amazontrust.com/r2m01.cer` (the leaf's AIA host) is
  byte-identical (DER) to the intermediate served by `novelpia.com`, and its
  SHA-256 fingerprint is `53:38:EB:EC:8F:B2:AC:60:99:61:26:D3:E7:6A:A3:4F:D0:F3:31:8A:C7:8E:BB:7A:C8:F6:F1:36:1F:48:4B:33`.
- The modern root fetched from
  `https://www.amazontrust.com/repository/AmazonRootCA1.pem` matches the
  fingerprint above and contains the AIA
  `http://ocsp.rootca1.amazontrust.com` (its own official distribution host).

## 5. Certificate source (official only)

| File in `assets/certs/` | Official source | SHA-256(DER) |
| --- | --- | --- |
| `AmazonRootCA1.pem` | `https://www.amazontrust.com/repository/AmazonRootCA1.pem` (Amazon Trust Services repository) | `8E:CD:E6:88:4F:3D:87:B1:12:5B:A3:1A:C3:FC:B1:3D:70:16:DE:7F:57:CC:90:4F:E1:CB:97:C6:AE:98:19:6E` |

The intermediate fingerprint is recorded above for provenance but the file is
**not bundled**; the app only ships the self-signed root.

References:

- Amazon Trust Services repository: <https://www.amazontrust.com/repository/>
- Amazon Trust Services signing certificates & cross-signed certs documentation
  (official CA page): <https://www.amazontrust.com/repository/>

> Note: direct machine downloads from `www.amazontrust.com` sometimes return
> 403 without a browser User-Agent. The bytes were fetched with a browser
> User-Agent and verified against the leaf's AIA host bytes and fingerprints
> above.

## 6. Origin policy, redirects and cookies

- **Host allow-list (`UrlPolicy`):** `novelpia.com` and any `*.novelpia.com`
  subdomain, over `https` on the **default port only**. URLs carrying
  userinfo, non-default ports, or malformed/backslash host forms are rejected
  before any connection is opened. Plaintext HTTP is impossible.
- **Redirects:** followed manually up to `MAX_REDIRECTS = 5`. Each hop must
  stay inside the allow-list; a hop that leaves it (or is non-HTTPS, or has
  userinfo/non-default ports) aborts with an `IOException`.
  - RFC 7231: `301`, `302` and `303` always become a **body-stripped `GET`**,
    even same-host.
  - `307`/`308` preserve `POST` method, body and content type **only on a
    same-host hop**.
  - A cross-host hop always becomes a body-stripped `GET`: sensitive form data
    never crosses a host boundary, and cookies stay host-scoped in the jar.
- **Cookies:** a per-instance `java.net.CookieManager` (memory-only store, no
  WebView cookie manager) using `CookiePolicy.ACCEPT_ORIGINAL_SERVER`, so a
  server can only read/write its own cookies and a foreign host can never plant
  or receive another host's session data. `clearCookies()` resets the session.
  The response header map is never mutated while storing (`getHeaderFields()`
  may be unmodifiable); `CookieManager.put` directly ignores the request-only
  `Cookie` header. Cookie emission happens **before** the POST body starts
  flowing, and failures are surfaced as `IOException` rather than silently
  swallowed. Cookies are never written to storage and never logged.
- **Referer:** each request sends `https://<host>/` (same-host origin) as the
  `Referer`. Query strings are not included.

## 7. Timeouts, response bounds, encoding

- Connect timeout `15 s`, read timeout `30 s`.
- Response body capped at `2 MiB` **after gzip inflation**
  (`BoundedBody.MAX_RESPONSE_BYTES`).
- Compressed wire bytes capped at `4 MiB`
  (`BoundedBody.MAX_COMPRESSED_BYTES`), counted at the raw stream layer — not
  through `GZIPInputStream`, which stops at the last gzip member boundary and
  can leave trailing wire bytes unread. The read loop drains to the real end
  of the response stream through the counting layer, so a `gzip` bomb or an
  oversized raw response is rejected with a `"… limit"` `IOException` in all
  cases (verified by unit tests in `app/src/test/…/net/`).
- `Content-Encoding: gzip` is decoded with `java.util.zip.GZIPInputStream`;
  other encodings are returned raw, bounded by the same caps.
- Bodies are decoded as UTF-8.
- Non-2xx statuses throw `HttpException` (an `IOException`) carrying the
  status code via `getStatusCode()` and a credential-free message built from
  the status code and host only.

## 8. Logging and privacy invariants

- No credentials, tokens, cookies, Authorization headers, or request/response
  bodies are ever logged.
- Error messages are constructed from status codes and host names only.
- `getDiagnosticsDescription()` exposes the TLS engine, protocol list, trust
  model, redirect policy, response caps and cookie policy — no secrets, no
  cookies, no bodies.
- `NetStatus.check()` loads the bundled root asset to prove the packaging is
  healthy and returns a validity/fingerprint summary or an error class name.

## 9. API surface

```java
NativeHttp http = new NativeHttp(context);    // throws IllegalStateException on TLS init failure
String html = http.get("https://novelpia.com/");          // IOException on failure
String out  = http.post("https://novelpia.com/proc/login", form); // IOException on failure
http.clearCookies();                                     // clears session cookies
```

Requests and session clearing are synchronous and serialized with the
NativeHttp instance lock. MainActivity supplies one background worker; the
network layer does not create another executor. Do not call it on the UI thread.
