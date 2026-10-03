# 외부 구성요소

앱은 원래 노벨피아 APK·아이콘·로고·책 표지·소설 본문을 번들로 포함하지 않습니다. 샘플 독서 문구는 이 프로젝트에서 작성한 테스트용 텍스트입니다.

| 구성요소 | 버전 | 사용 | 라이선스 / 공식 출처 |
|---|---|---|---|
| Conscrypt Android | 2.5.2 | 앱 내부 TLS 엔진 | Apache-2.0, https://github.com/google/conscrypt |
| jsoup | 1.15.4 | HTML 파싱, 웹뷰 없음 | MIT, https://jsoup.org/license |
| Amazon Root CA 1 | PEM 고정 | 추가 루트 신뢰 앵커 | https://www.amazontrust.com/repository/ |
| JUnit | 4.13.2 | 호스트 테스트 전용, 앱에 미포함 | EPL-1.0, https://junit.org/junit4/ |
| Hamcrest Core | 1.3 | 호스트 테스트 전용, 앱에 미포함 | BSD-3-Clause, https://hamcrest.org/ |
| NanumGothic Regular | 정적 TTF | 한글 UI / 본문 글꼴 | SIL OFL-1.1, Google Fonts `ofl/nanumgothic` |
| org.json | 20140107 | 호스트 테스트 전용, 앱에서는 Android 제공 구현 사용 | JSON License, Maven Central `org.json:json` |

의존성 다운로드 주소와 SHA-256은 `tools/build.py`에서 확인할 수 있습니다. CA 파일의 출처·지문은 `app/src/main/assets/certs/README.md`를 참고하세요.

`fonts/NanumGothic-Regular.ttf`의 SHA-256은
`76f45ef4a6bcff344c837c95a7dcc26e017e38b5846d5ae0cdcb5b86be2e2d31`입니다.
라이선스는 `assets/licenses/NanumGothic-OFL.txt`에 포함했습니다.

`app/src/main/assets/licenses/`에 jsoup MIT 라이선스와 Conscrypt 원본
LICENSE/NOTICE 및 Apache-2.0 전문을 포함했습니다. 개발/개인 테스트
프리뷰이며, 외부 배포 전에는 추가 네이티브 전이 의존성의 라이선스·NOTICE와
서비스 약관을 별도로 점검해야 합니다. Android SDK/에뮬레이터는 해당 Google
SDK 라이선스에 따라 별도로 내려받으며 저장소에 재배포하지 않습니다.
