# 밀리 APK 내부 TLS 교체 가능성 분석

> **실행 검증 추가 (2026-09-28):** `docs/MILLIE_APK_TLS_AVD_TEST.md`에 실제 API19 ARM AVD 결과를 기록했다. 독립 APK의 내장 Conscrypt는 밀리 공개 서버 3곳에 TLS 1.2/1.3 연결에 성공했다. 다만 밀리 APK는 서명만 바꿔도 지연 네이티브 충돌이 발생해, 실사용 가능한 밀리 TLS 수정본을 완성한 것은 아니다. 아래 본문은 그 전에 수행한 정적 분석 기록이다.

분석일: **2026-09-28**. 대상은 블로그에 연결된 **밀리의 서재 e-ink 2.1.0.0** 한 버전이다.
교보 앱이나 최신 밀리 일반 Android 앱에 같은 결론을 자동 적용하지 않는다.

## 결론

**APK 내부에 TLS 엔진을 추가하는 방식은 기술적으로 가능하다.**
이 APK의 로그인·서재에는 실제로 교체 가능한 Retrofit/OkHttp 경로가 있다.
그러나 **원본과 같은 기능을 모두 유지하며 Android 4.4에서 작동하는 수정 APK를
완성·실행 검증한 것은 아니다.** AppGuard·재서명, 다중 네트워크 경로, WebView,
API 19용 TLS 구현의 장기 보안 유지가 별도 과제다.

- 로그인/서재의 Java TLS 교체: **수정 지점 확인, 가능성이 높음**.
- 루팅·시스템 CA 변경·iptables 없이 앱 내부 처리: **설계상 가능**.
- 원본 APK에 TLS 라이브러리를 넣는 것만으로 모든 기능 해결: **아님**.
- 수정·재서명본의 정상 시작/로그인/다운로드/독서: **미검증**.
- AppGuard나 DRM·구매/구독 권한 우회: **수행하지 않음**.

## 1. 분석한 원본과 도구

| 항목 | 확인값 |
|---|---|
| 다운로드 | `https://apis.millie.co.kr/v1/download/apk/millie-app-2.1.0.0.apk` |
| 패키지 | `kr.co.millie.eink` |
| versionName / versionCode | `2.1.0.0` / `210` |
| minSdk / targetSdk | `19` / `31` |
| 크기 | 56,512,611 bytes |
| SHA-256 | `d72c49fe63f98af3972054cee62e4a6c5edd92061e0aed159ec289da96673d8d` |
| ABI | armeabi-v7a / arm64-v8a / x86 / x86_64 |
| DEX | classes.dex ~ classes5.dex, 총 5개 |
| 원본 서명 검사 | apksigner, min-sdk 19: v1/v2/v3 검증 통과 |
| 서명 인증서 SHA-256 | `b90307648414c39046db571ac9ec24c6187c9e2fc0128685051bbbc2bbde2f21` |
| 정적 분석 | JADX 1.5.1, JRE 17. 전체 처리 완료하였으나 부분 역컴파일 오류 19건 |

오류가 난 메서드를 Java 원본처럼 신뢰하지 않았다. 특히 LoginAgent의
난독화된 dispatch 메서드 내부 전체 동작은 복원 완료로 표시하지 않는다.
JADX 출력은 그대로 재컴파일 가능한 원본 프로젝트가 아니다.

보관 위치:

- `build/audit/millie-2.1.0.0/original.apk`: 변경하지 않은 공식 APK
- `build/audit/millie-2.1.0.0/signature.txt`: 원본 서명 검사
- `build/audit/millie-2.1.0.0/evidence.json`: 해시·manifest·정적 검사 결과
- `/tmp/millie-apk-audit/decompiled/`: 일시적인 역컴파일 산출물

`build/`는 Git 제외 대상이다. APK/역컴파일 소스를 배포물에 넣지 않았다.
JADX 배포 ZIP SHA-256:
`12fd966431903b8e15c36e5007f19343475be7d8f2a55f082e7a929eeabc937e`.

## 2. APK에서 직접 확인한 네트워크 경로

아래 경로는 `/tmp/millie-apk-audit/decompiled/sources/` 기준이다.
행 번호는 이번 JADX 출력 기준으로 재분석 시 달라질 수 있다.

| 경로 / 근거 | 의미 |
|---|---|
| `kr/co/millie/eink/user/LoginAgent.java:43–61` | 네이티브 로그인은 Retrofit, `live-api.millie.co.kr/v1/`, POST login. 생성자에서 `createOkHttpClient()` 연결 |
| `okhttp3/internal/Version.java:6` | 내장 OkHttp `3.12.12` |
| `kr/co/millie/eink/user/ApisAgent.java:118–140` | 신/구 API용 Retrofit과 자체 OkHttp Builder |
| `user/UserAgent.java`, `user/ViewerStateAgent.java`, `bookshelf/BookshelfAgent.java` | 여러 독립 API 클라이언트 생성 지점 |
| `bookshelf/DownloadAgent.java`, `search/apis/DownloadAPIs.java`, `pdf/PDFAgent.java` | 다운로드 관련 OkHttp 클라이언트도 별도로 존재 |
| `kr/co/millie/core/utils/HttpUtilsKt.java:22–34` | 일부 코어 기능의 공통 OkHttp Builder |
| `okhttp3/OkHttpClient.java:566–576` | `sslSocketFactory(SSLSocketFactory, X509TrustManager)` 주입 지점 존재 |
| `kr/co/millie/core/utils/LocalServerAgent.java:539–561` | 별도의 `URL.openConnection()` 원격 리소스 경로도 존재 |
| `search/view/SearchWebView.java:66–73` | 검색 UI는 APK에 포함된 HTML을 WebView로 로드 |
| `resources/assets/searchPage/dist/assets/index-legacy.84dd03eb.js` | XMLHttpRequest/Axios 및 실제 `live-api.millie.co.kr` 검색·`apis.millie.co.kr` 상세 요청 코드 |

따라서 로그인 클라이언트 한 곳만 수정하면 다운로드나 검색은 여전히
구형 스택을 사용할 수 있다. 반대로 검색 UI가 file://로 시작한다는 이유로
인터넷 통신이 없다고 판단해서도 안 된다. JS에 실제 원격 요청이 있다.

네이티브 파일은 PDF/Realm 관련 파일 외 `libloader.so`, `libdiresu.so`도 포함한다.
이 목록만으로 모든 네이티브 통신 경로가 없다고 주장하지 않는다.

## 3. “TLS 1.2를 켜기만 하면 된다”는 결론은 부정확

Android 공식 SSLSocket 문서에서 TLS 1.2는 API 16부터 지원하며
클라이언트 기본 활성화는 API 20부터다. Android 4.4/API 19가
TLS 1.2 자체를 전혀 구현하지 않았다는 의미가 아니다.

더 중요한 APK 증거:

- `okhttp3/internal/platform/AndroidPlatform.java:375–391`은 API 22 미만에서
  먼저 `SSLContext.getInstance("TLSv1.2")`를 요청한다.
- `okhttp3/ConnectionSpec.java:27–32`에 TLS 버전·암호군 정책이 있다.

따라서 이 앱에는 단순 버전 플래그 외에 신뢰 루트, 암호군/서명 알고리즘,
제조사 구현, SNI/ALPN, 서버 정책, 실제 실패 요청을 구분하는 검증이 필요하다.
과거 블로그 장애의 정확한 원인을 이번 정적 분석으로 재현한 것은 아니다.

호스트 PC의 OpenSSL로 공개 서버 4곳(live-api / api / apis / install.millie.co.kr)에
TLS 1.2 핸드셰이크를 수행한 결과 `ECDHE-RSA-AES128-GCM-SHA256`,
인증서/호스트명 검증 OK를 확인했다. **이는 크레마에서 성공했다는 뜻이 아니다.**
별도 구형 CBC 제한 실험은 핸드셰이크 완료 근거가 없어 결론에서 제외했다.

## 4. 안전한 APK 내부화 설계안

### Java/OkHttp 경로

1. API 19/ARMv7에서 실제 구동 가능한 TLS provider와 JNI를 APK에 포함한다.
2. 그 provider를 명시한 SSLContext와 검증 가능한 TrustManager를 만든다.
3. 필요한 각 OkHttp Builder에 socket factory와 **동일 TrustManager**를 함께 주입한다.
4. 원래 호스트명 검증·인증서 pin 정책·쿠키·인증 헤더·요청 의미를 유지한다.
5. 별도 URLConnection 경로에도 같은 정책을 적용한다.
6. API 19 multidex 로딩 이후, 첫 TLS 클라이언트 생성 이전에 초기화되도록 검증한다.

이 APK의 `Platform.findPlatform()`은 Android이면 AndroidPlatform 계열로
들어가며 Conscrypt 선호 검사는 JVM 분기에 있다 (`Platform.java:125–156`).
그러므로 “provider를 첫 번째로 등록하면 모든 플랫폼 분기와 ALPN까지
자동 전환된다”는 가정을 두지 않는다. 명시적 주입과 실제 핸드셰이크 검증이 필요하다.

**인증서 검증 끄기, 모든 호스트명 허용, SSL 오류 무시 방식은 사용하지 않는다.**
노벨피아에 맞춘 AmazonRootCA1 하나를 밀리에도 충분한 인증서 묶음이라고
그대로 복사해서는 안 된다. 대상 호스트의 신뢰 경로를 별도로 확인해야 한다.

### WebView 경로

Java TLS provider를 추가했다고 검색 JS의 통신까지 교체됐다고 가정할 수 없다.
검색 요청을 제한된 네이티브 네트워크 경로로 연결하는 등의 별도 수정이 필요하다.
API 19의 `shouldInterceptRequest(WebView,String)`은 URL만 받는다.
메서드·헤더 정보를 제공하는 WebResourceRequest API는 API 21부터이므로
현대 Android용 포워딩 코드를 그대로 이식할 수 없다.
검색/인증/이미지/리다이렉트 동작을 각각 테스트해야 한다.

### 엔진 버전 유지보수

프로젝트에 있는 Conscrypt 2.5.2 AAR는 manifest minSdk **9**이며,
ARMv7 JNI를 포함한다. 기존 문서에서 라이브러리 자체 minSdk를 19라고
표현한 부분과 구분해야 한다(이 프로젝트 앱의 minSdk는 19).

현재 Conscrypt 공식 main README의 Android 지원 하한은 API 21이다.
따라서 최신 라이브러리 번호로 교체하면 자동으로 KitKat까지 지원된다고
가정할 수 없다. 구버전을 이용한 호환성 PoC와 장기 보안 업데이트가 가능한
유지보수 빌드는 별개다. 여기서 구형 라이브러리의 현시점 무취약성을 보증하지 않는다.

## 5. 실행/재서명 측면의 큰 제약

- Manifest Application은 `android.support.v4.soft.ApplicationMain`이다.
- `BuildConfig.java:8`에서 `APPGUARD_ENABLED=true`.
- `GlobalApplication.java:27–59`에서 AppGuard/Diresu 초기화가 확인된다.
- APK에는 보호 관련 loader/JNI가 포함되어 있다.

이것은 보호 코드가 있다는 증거이지, 수정 APK가 반드시 차단된다는
실행 증거는 아니다. **재서명·코드 변경 후 정상 실행 여부가 중요한 선행 실험**이다.
보호 코드 비활성화·우회는 이번 분석에서 시도하지 않았다.

또한 원본 서명 키가 없으면 기존 정식 앱과 같은 서명으로 업데이트할 수 없다.
Android의 서명 일치 조건 때문에 일반적인 재서명본은 기존 앱 위에
업데이트로 설치되지 않는다. 삭제/다른 패키지로 설치하면 데이터 보존,
로그인 및 앱 식별자 연동을 별도로 다뤄야 하므로 실사용 기기에서 바로 시험하면 안 된다.

## 6. 다음 단계 — 아직 수행하지 않은 검증

1. 별도 API 19 테스트 환경에서 공식 원본의 정상 시작 상태를 기준으로 기록.
2. 실제 계정/책 없이 재서명·재패키징 자체의 영향부터 비교.
   원본과 수정본 비교 없이 에뮬레이터 탐지와 변조 탐지를 혼동하지 않음.
3. 실행 조건이 확인되면 Java 네트워크 한 경로에 TLS/TrustManager를 주입하는 PoC.
4. 공개 버전 확인 요청의 TLS/HTTP/응답 파싱을 단계별 검증.
5. 사용자 직접 로그인 후 서재·정상 권한 다운로드·WebView 검색·독서·복귀 검증.
6. 시스템 CA/브릿지/iptables를 변경하지 않은 환경인지 확인한 뒤 대체 가능 판정.

**현재는 1–6을 완료한 수정 APK가 없다.** 이 문서는 설계 가능성·정적 근거에
대한 답이며, 이미 작동하는 브릿지를 제거하라는 권고가 아니다.
기존 노벨피아 앱 코드, 크레마/AVD 설치 상태, 계정, 시스템 설정은 변경하지 않았다.

## 근거 URL

- 사용자 블로그: https://blog.hegelty.me/15
- 공식 APK: https://apis.millie.co.kr/v1/download/apk/millie-app-2.1.0.0.apk
- Android SSLSocket: https://developer.android.com/reference/javax/net/ssl/SSLSocket
- Android 앱 서명: https://developer.android.com/studio/publish/app-signing
- WebViewClient: https://developer.android.com/reference/android/webkit/WebViewClient
- WebResourceRequest: https://developer.android.com/reference/android/webkit/WebResourceRequest
- Conscrypt 현재 소스/지원 범위: https://github.com/google/conscrypt
- Conscrypt 2.5.2 당시 README: https://raw.githubusercontent.com/google/conscrypt/2.5.2/README.md
- JADX 1.5.1: https://github.com/skylot/jadx/releases/tag/v1.5.1

앱 고유 구현에 관한 판단은 위 원본 APK의 이번 정적 분석을 근거로 한다.
공식 문서의 일반적인 TLS/서명 설명이 밀리 수정 APK의 실행 성공을 증명하지는 않는다.
