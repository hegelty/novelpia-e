# 외부 구성요소

앱은 원래 노벨피아 APK·아이콘·로고·책 표지·소설 본문을 번들로 포함하지 않습니다. 샘플 독서 문구는 이 프로젝트에서 작성한 테스트용 텍스트입니다.

| 구성요소 | 버전 | 사용 | 라이선스 / 공식 출처 |
|---|---|---|---|
| Conscrypt Android | 2.5.2 | 앱 내부 TLS 엔진 | Apache-2.0, https://github.com/google/conscrypt |
| BoringSSL | Conscrypt 2.5.2 Android AAR에 포함 | 네이티브 TLS / 암호화 | OpenSSL, SSLeay, ISC, fiat-crypto MIT, 아래 출처 설명 참고 |
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

`app/src/main/assets/licenses/`에 jsoup MIT 라이선스, NanumGothic OFL,
Conscrypt 원본 LICENSE/NOTICE, Apache-2.0 전문과 아래 BoringSSL 고지를
포함했습니다. Conscrypt NOTICE에는 Apache-2.0으로 배포되는 Netty와
Apache Harmony 부분에 대한 고지도 포함되어 있습니다. 외부 구성요소는
각자의 라이선스를 유지하며 프로젝트의 GPLv3로 재라이선스하지 않습니다.
프로젝트의 GPLv3 적용 범위와 Conscrypt/BoringSSL 추가 허가는 저장소 루트의
`LICENSE`와 `LICENSE-EXCEPTION`을 참고하세요.

Android SDK/에뮬레이터와 테스트 전용 의존성은 APK에 포함하지 않습니다.
SDK/에뮬레이터는 해당 Google SDK 라이선스에 따라 별도로 내려받으며
저장소에 재배포하지 않습니다.

## BoringSSL 고지의 출처

Conscrypt Android 2.5.2의 공식 Maven Central AAR에 포함된
`classes.jar!/org/conscrypt/conscrypt.properties`에는 BoringSSL revision이
`207b6541e1bc619d96e0cb215069f062d6672b61`로 기록되어 있습니다.
공개 배포를 준비하면서 Google BoringSSL, Android 및 Chromium 저장소에서
이 revision의 소스와 LICENSE를 조회했지만 찾지 못했습니다.

따라서 `app/src/main/assets/licenses/BoringSSL-LICENSE.txt`에는 같은
Conscrypt 2.5.2 릴리스의 **OpenJDK 배포물**이 기록한 공개 BoringSSL revision
`49f0329110a1d93a5febc2bceceedc655d995420`의 LICENSE를 보존했습니다.
이 파일을 Android AAR의 정확한 revision에서 추출했다고 주장하지 않습니다.
아래 공개 소스 snapshot 역시 Android에 포함된 네이티브 바이너리와
정확히 일치한다고 검증된 소스가 아닙니다.

- 공식 LICENSE: https://boringssl.googlesource.com/boringssl/+/49f0329110a1d93a5febc2bceceedc655d995420/LICENSE
- LICENSE SHA-256: `60bd7c54856bf9387221bde5ab55d516d7cea15870d0fed69406bcd1c8ec7c9d`
- 공개 소스 snapshot: https://boringssl.googlesource.com/boringssl/+/49f0329110a1d93a5febc2bceceedc655d995420/

이 LICENSE에는 OpenSSL, Original SSLeay, Google ISC 및 fiat-crypto MIT
조건이 포함되어 있습니다. 함께 기재된 Go/Chromium 지원 코드의 조건은
해당 파일에 설명된 범위에 적용됩니다.

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). This product includes software written by Tim Hudson
(tjh@cryptsoft.com).

## 버전별 공식 소스

- Conscrypt 2.5.2 소스 및 빌드 스크립트: https://github.com/google/conscrypt/tree/2.5.2
- Conscrypt Android 2.5.2 AAR: https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.5.2/conscrypt-android-2.5.2.aar
- Conscrypt Android 2.5.2 Java 소스: https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.5.2/conscrypt-android-2.5.2-sources.jar
- jsoup 1.15.4 소스 및 빌드 스크립트: https://github.com/jhy/jsoup/tree/jsoup-1.15.4
- jsoup 1.15.4 Java 소스: https://repo.maven.apache.org/maven2/org/jsoup/jsoup/1.15.4/jsoup-1.15.4-sources.jar

Conscrypt Java 소스 JAR만으로는 네이티브 BoringSSL 소스가 제공되지 않습니다.
배포된 라이브러리 바이너리의 다운로드 주소와 SHA-256은 `tools/build.py`에
고정되어 있습니다.
