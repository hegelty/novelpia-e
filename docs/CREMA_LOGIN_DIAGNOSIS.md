# 크레마 로그인 판정 실패 진단 — 2026-09-27

실기기: CREMA_0670C, Android 4.4.2 / API 19, armeabi-v7a.
fastboot에서 일반 부팅으로 전환해 ADB로 진단했다.

## 원인

사용자가 재현한 문구는 `로그인 상태를 확인하지 못했습니다.`였다.
이 문구는 TLS/POST 실패가 아니라 AccountClient의 HTML 세션 판정이
UNKNOWN을 반환하는 경로다.

실기기에 전달된 공개 `/mybook/last_view` 응답은 다음 선언을 사용했다.
아래 값은 합성 예시이며 실제 계정 정보를 수집하지 않았다.

```javascript
var _top_obj = { data: { mem_adt: "0", mem_no: "0" }, methods: {} };
```

기존 정규식은 `const _top_obj`만 허용했다. 동일 조사 중 PC에서 받은
공개 응답은 `const`였으므로 선언 형태를 하나로 가정할 수 없다.
응답 차이가 서버·중간 계층 중 어디서 발생했는지는 확인하지 않았다.
비로그인 페이지는 별도의 로그인 리다이렉트 구문으로 판정할 수 있지만,
로그인 페이지가 `var` 선언을 사용하면 기존 회원 표식 검사가 실패한다.

## 확인한 사항

- 설치 APK는 수정 전 dist APK와 SHA-256이 동일했다.
- 기기 시각 정상. Conscrypt 2.5.2 네이티브 라이브러리 로드 성공.
- 동일 NativeHttp로 두 공개 호스트 HTTPS GET, 세션 페이지 GET,
  자격증명 없는 빈 로그인 POST 성공. 비로그인 서재 AJAX는 정상적으로 401을 반환.
- 실기기 합성 CookieManager 검사 통과.
- 실제 공개 HTML에서 기존 표식 정규식은 실패하고 합성 `const` 표식은 성공.
- 별도 현상: 화면 절전 10초 후 PowerManagerEx가 Wi-Fi를 끄며 통신이
  실패했다. 화면이 깨어나고 Wi-Fi가 복구되면 요청이 성공했다.
  이는 사용자가 보고한 HTML 판정 실패 문구와 구분한다.

## 수정 및 검증

`AccountClient.TOP_MEMBER_NO`가 `const`, `let`, `var`를 허용하도록 수정했다.
`_top_obj.data.mem_no` 확인 및 methods 영역 배제는 유지한다.
각 선언의 로그인/비로그인 판정과 무관한 회원 필드 거부 회귀 테스트를 추가했다.
수정 전 테스트 실패를 확인했고 수정 후 전체 JUnit 169개 및 APK 서명 검증이 통과했다.
수정 빌드의 실제 AccountClient 클래스를 별도 진단 앱에서 실행해 크레마에서도
합성 회원 표식의 세 선언을 모두 AUTHENTICATED로 판정하는 것을 확인했다.
실제 공개 세션 응답도 수정 파서에서 UNAUTHENTICATED로 정상 판정됐으며,
절전 해제 후 공개 HTTPS/POST와 합성 쿠키 검사가 다시 통과했다.
수정 APK는 기존 데이터를 유지하는 `adb install -r`로 실기기에 적용했다.

빌드: Android 36 컴파일 SDK, minSdk 19, build-tools 37.0.0,
로컬 JetBrains JDK. 빠진 jar 실행 파일만 임시 Python ZIP 래퍼로 보완했다.
이번 빌드는 API 19 android.jar 컴파일 검사가 아니다.

수정 APK SHA-256:
`2b5e8623504f49e2da61294302e5bcf8be4244df433a29a09f90bc9e92569331`.

실계정 로그인 성공은 사용자 재로그인으로 별도 확인해야 한다.
자격증명, 실제 세션 쿠키, 인증된 응답 본문은 진단에 수집하지 않았다.
