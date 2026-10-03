# 밀리 e-ink APK / Android 4.4 AVD 실제 검증

검증일: **2026-09-28 (한국 시간)**.
대상: 블로그에 연결된 **밀리의 서재 e-ink 2.1.0.0**, `kr.co.millie.eink`.
노벨피아 앱이나 교보 앱에 이 결과를 그대로 적용하지 않는다.

## 결론

1. **시스템 CA나 TLS 브릿지를 바꾸지 않고, APK에 포함한 TLS 엔진으로
   Android 4.4.2에서 밀리 서버와 연결하는 것은 실제 AVD에서 성공했다.**
   독립 검증 APK의 Conscrypt 2.5.2 + OkHttp 3.12.12 조합으로 공개 서버
   세 곳에 TLS 1.2 / 1.3 연결 후 HTTP 응답을 받았다.
2. **밀리 원본 APK를 수정해서 실사용 가능한 앱을 만든 것은 아니다.**
   먼저 수행한 서명 변경 대조군이 로그인 화면에 도달한 뒤 네이티브
   SIGSEGV로 반복 종료됐다. 원본을 다시 설치하면 같은 충돌은 관찰되지 않았다.
3. 따라서 **TLS 엔진 내장의 가능성**과 **밀리 수정·재서명 APK의 실행 안정성**은
   별개의 문제다. 재서명 후 충돌의 원인을 해결하기 전에는 블로그의 기존
   방식을 제거하거나 수정 APK로 대체하라고 권할 수 없다.

**TLS 엔진을 밀리 APK에 실제로 삽입한 결과가 아니라,
독립 엔진 검증과 원본/재서명 대조 실험을 구분한 결과다.**
밀리 보호 코드, DRM, 구매·구독 권한을 변경하거나 우회하지 않았다.

## 1. 환경 및 제한

| 항목 | 실제 확인값 |
|---|---|
| AVD | 새로 생성한 `Millie19ARM`, 기존 사용자 AVD와 분리 |
| Android | 4.4.2 / API 19 |
| ABI | armeabi-v7a |
| 이미지 | Google API19 ARM r05, generic SDK 이미지 |
| 화면 | 600 × 800, 160 dpi |
| 메모리 | 설정 1024MB, 게스트 `MemTotal: 748964 kB` (약 731MiB) |
| 실행 | classic ARM 소프트웨어 에뮬레이션 |
| ADB | 별도 서버 포트 5038, `emulator-5582` |
| 사용자 계정 | 입력하지 않음 |
| 시스템 CA / 시간 / TLS 브릿지 | 변경하지 않음 / 변경하지 않음 / 사용하지 않음 |
| 기존 노벨피아·사용자 기기 | 변경하지 않음 |

크레마 실기기와 Android API/32비트 ARM은 가깝지만, 제조사 펌웨어,
인증서 저장소, 실제 1GB 메모리, e-ink, 물리 버튼까지 동일한 환경은 아니다.
AVD의 ADB는 root 셸이지만 검증 앱은 일반 앱 UID로 실행된다.
ADB 권한은 설치·로그·테스트 결과 회수에만 사용했고 시스템 신뢰 설정은 바꾸지 않았다.

앞서 시도한 x86 AVD는 **밀리 설치 전** 시스템 서버가 시스템 `libcrypto`의
`bn_mul_mont`에서 충돌했으므로 앱 평가에서 제외했다. ARM AVD는 임시 공간
부족 때 생성된 불완전한 데이터 이미지를 공식 초기 이미지로 재생성한 후
`sys.boot_completed=1`을 확인하고 실험했다.

## 2. 원본 → 재서명본 → 원본 재설치

재서명본은 서명 메타데이터를 제외한 **1169개 ZIP 항목의 내용이 모두 동일**하다.
DEX, manifest, 리소스, 네이티브 라이브러리, 보호 코드는 수정하지 않았다.
v1/v2 서명 검증이 통과했으며 원본과 다른 일회성 연구용 키로 서명했다.

| 조건 | 설치 | 실제 화면/로그 |
|---|---|---|
| 공식 원본 | 성공 | LoginActivity 표시. 시작 시 SSLHandshakeException. 관찰 구간에서 네이티브 충돌 없음 |
| 서명만 변경 | 성공 | LoginActivity 표시 후 앱 중지 팝업 및 반복 SIGSEGV |
| 공식 원본 재설치 | 성공 | 120초 대기 및 후속 관찰에서 동일 프로세스 유지, 새 Fatal signal 0, 로그인 화면 유지 |

원본에서 포착한 TLS 예외:

```text
javax.net.ssl.SSLHandshakeException
SSL23_GET_SERVER_HELLO:tlsv1 alert protocol version
okhttp3.internal.connection.RealConnection.connectTls
```

재서명본에서 포착한 네이티브 예외:

```text
Fatal signal 11 (SIGSEGV), fault addr 0000001f
>>> kr.co.millie.eink <<<
#00 pc 001cfb36 /data/data/kr.co.millie.eink/<임시 경로> (deleted)
#01 pc 001cf9bf /data/data/kr.co.millie.eink/<임시 경로> (deleted)
```

보관된 발췌에는 서로 다른 PID에서 반복된 충돌이 있다.
**재서명/무결성 처리와 관련된 실패를 의심할 근거는 있지만,
이 로그만으로 AppGuard의 특정 검사가 원인이라고 확정하지 않는다.**
`AppGuard` 문자열 횟수 자체도 차단 증거가 아니다. 원본에서도 최신 API를
참조하는 Dalvik 경고에 이 문자열이 나온다.

재설치 도중 이전 재서명본의 시스템 오류 팝업 두 개가 남아 있었다.
원본 UID 10054/PID 1908과 이전 충돌 UID 10053을 구분했고, 이전 팝업을
닫은 후 화면을 다시 기록했다. `original-retry-obscured.*`는 그 중간 증거이며
최종 원본 판정 화면은 **`original-retry.png`**다.

로그인 화면 도달은 로그인 성공을 뜻하지 않는다. 계정 인증·서재·책 열기는
실험하지 않았다. 첫 실행의 멀티덱스 최적화 때문에 `am start -W`가 timeout을
반환하기도 했지만, 이후 실제 화면·프로세스를 확인해 시작 여부를 판정했다.

## 3. 독립 APK 내부 TLS 엔진 실험

패키지: `me.crema.millietlsprobe`. 밀리 APK와 별개로 만든 작은 검증 앱이다.

- Conscrypt 2.5.2 Java 클래스와 ARMv7 JNI를 APK에 포함.
- OkHttp 3.12.12 / Okio 1.15.0 사용.
- 시스템 기본 X509TrustManager를 모든 조건에서 동일하게 사용.
- provider는 SSLContext에 명시적으로 전달. 시스템 provider/CA 저장소는 변경하지 않음.
- 기본 OkHttp 호스트명 검증 유지. trust-all/SSL 오류 무시 사용하지 않음.
- `HEAD` 요청만 전송. 리다이렉트·재시도는 끄고 HTTP/1.1로 통일.
- 쿠키·로그인 토큰·비밀번호·본문 다운로드 없음.
- 시스템 대조군은 SSLContext 선택뿐 아니라 **각 소켓의 enabledProtocols를
  TLSv1.2로 명시적으로 설정**하고 ConnectionSpec도 TLS 1.2로 제한.
- Conscrypt도 TLS 1.2 고정 조건을 따로 시험해 단순히 TLS 1.3 차이인지 구분.

### 최종 결과

| 공개 요청 대상 | 시스템 TLS 1.2 강제 활성화 | 내장 Conscrypt 자동 협상 | 내장 Conscrypt TLS 1.2 고정 |
|---|---|---|---|
| `live-api.millie.co.kr/v1/` | SSLException / ZERO_RETURN | TLS 1.3 / HTTP 404 | TLS 1.2 / HTTP 404 |
| `api.millie.co.kr/V2/` | SSLException / ZERO_RETURN | TLS 1.3 / HTTP 403 | TLS 1.2 / HTTP 403 |
| `apis.millie.co.kr/` | SSLException / ZERO_RETURN | TLS 1.3 / HTTP 404 | TLS 1.2 / HTTP 404 |

협상된 암호군:

- TLS 1.3: `TLS_AES_128_GCM_SHA256`
- TLS 1.2: `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`

**HTTP 403/404는 로그인이나 API 기능 성공이 아니다.**
여기서는 인증서와 호스트명 검증을 유지한 HTTPS 연결이 완료되고
서버의 HTTP 응답까지 수신했다는 증거로만 사용한다.
시스템 대조군의 정확한 협상 실패 원인을 암호군 하나로 단정하지도 않는다.

### 인증서 검증 음성 대조군

동일한 시스템 TrustManager에 이번 연구용으로 생성된 신뢰되지 않는 자체 서명
인증서를 전달했다. `checkServerTrusted`가 이를 거부했다.

```text
untrustedSelfSignedRejected = true
hostnameVerifierOverridden = false
systemTrustModified = false
trustedIssuerCount = 150
```

이는 trust-all이 아니었다는 확인이다. 호스트명 불일치 서버를 이용한 별도의
음성 네트워크 테스트나 모든 인증서 공격에 대한 검증까지 수행한 것은 아니다.
크레마의 인증서 저장소가 이 SDK 이미지와 동일하다는 의미도 아니다.

### 중간 실험을 최종 결과와 구분

- `tls-probe-huc.*`: KitKat 기본 HttpsURLConnection을 사용한 첫 실험.
  Conscrypt가 한 서버에서는 TLS 1.3 연결됐지만 다른 두 서버에서는
  `No enabled protocols; SSLv3 ... filtered`가 발생했다. provider를 넣는
  것만으로 구형 HTTP 경로가 모두 해결된다고 판단하지 않는다.
- `tls-probe-context-only.*`: OkHttp 전환 직후, SSLContext/ConnectionSpec만
  TLS 1.2로 선택한 중간 실험. 시스템 소켓의 enabledProtocols가 여전히
  SSLv3/TLSv1인 상태여서 거절됐다. **TLS 1.2를 실제 켠 대조군으로 사용하지 않는다.**
- **`tls-probe.json` / `tls-probe.png`가 소켓 활성화까지 수정한 최종 9개 결과다.**

독립 앱은 Java 7 바이트코드, D8 min API 19로 빌드했다. OkHttp의 사용하지 않는
Duration 오버로드 때문에 최종 컴파일 stub은 API36을 사용하되, 실제 코드는
API19에서 지원되는 long/TimeUnit 메서드를 호출한다. Conscrypt의 일부 상위
API/숨김 타입에 대한 D8 경고가 있었으며, 이번에 실제 호출한 경로의 성공만
주장한다. APK의 v1/v2/v3 서명 검증도 통과했다.

## 4. 산출물

모든 경로는 프로젝트 루트 기준이다. `build/`는 Git 제외 대상이다.

- `build/audit/millie-2.1.0.0/avd-results/environment.json`
- `build/audit/millie-2.1.0.0/avd-results/original-retry.png`
- `build/audit/millie-2.1.0.0/avd-results/resigned-60.png`
- `build/audit/millie-2.1.0.0/avd-results/resigned-crash.txt`
- `build/audit/millie-2.1.0.0/avd-results/tls-probe.png`
- `build/audit/millie-2.1.0.0/avd-results/tls-probe.json`
- `build/audit/millie-2.1.0.0/avd-results/capture_test.py`
- `build/audit/millie-2.1.0.0/resign-control/results.json`
- `build/audit/millie-2.1.0.0/resign-control/resign-only.apk`
- `build/audit/millie-2.1.0.0/tls-probe/tls-probe.apk`
- `build/audit/millie-2.1.0.0/tls-probe/src/me/crema/millietlsprobe/MainActivity.java`
- `build/audit/millie-2.1.0.0/tls-probe/build.py`
- `build/audit/millie-2.1.0.0/tls-probe/build-log.txt`
- `build/audit/millie-2.1.0.0/tls-probe/build-evidence.json`

`capture_test.py`의 15/60/120 이름은 실험 체크포인트 이름이며 정확한
앱 시작 후 경과 시간으로 해석하지 않는다. ADB 시작 대기, 소프트웨어
에뮬레이션, UIAutomator 캡처 시간이 추가된다.

### APK SHA-256

```text
공식 원본:
d72c49fe63f98af3972054cee62e4a6c5edd92061e0aed159ec289da96673d8d

서명만 변경한 대조군:
f291e976f83bddb8d48aff7f63d9130696dc5ab59faca7b01f85553c6c9d5adf

최종 독립 TLS 검증 앱:
053ef17c39b9240d5dd839258dde7535a6d4c32696597ec523a5e5efacee018f
```

독립 검증 APK와 재서명 대조군은 연구 산출물이지 실사용 밀리 앱 업데이트가 아니다.
빌드 스크립트에는 당시 SDK/JRE/의존성/일회성 키 경로가 기록되어 있으며,
환경이 제거되면 경로와 키를 다시 준비해야 한다. 개인 사용자 키를 사용하지 않았다.

## 5. 남은 검증과 판단

- 밀리 APK의 재서명 후 네이티브 충돌 원인, 실기기에서의 재현 여부.
- 밀리 자체 네트워크 경로에 TLS를 주입한 뒤 로그인·서재·다운로드·검색·독서.
- 구형 WebView의 별도 네트워크 경로.
- 크레마 펌웨어의 실제 루트 CA 목록 및 네이티브 호환성.
- 구버전 Conscrypt의 장기 보안 업데이트 방안.

이번에는 재서명 단계에서 안정성 문제가 확인되어 **밀리 TLS 주입본 제작은
진행하지 않았다.** 엔진 사용 가능성만으로 이 단계를 성공했다고 간주하지 않는다.
기존 정적 분석은 `docs/MILLIE_APK_TLS_FEASIBILITY.md`를 참고한다.
