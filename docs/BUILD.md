# 빌드 및 개발 안내

노벨피아e는 Android 기본 위젯과 Java로 작성한 앱이다. 최소 Android 버전은
4.4(API 19)이며, application ID는 업데이트 호환성을 위해 `me.crema.novelia`를 유지한다.

## Linux / WSL 수동 빌드

Python 3, JDK 17 이상, Android SDK platform 및 build-tools 35/36이 필요하다.

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/Android/Sdk
python3 tools/build.py
# API 19 호환성 검사와 별도 검증 APK:
python3 tools/build.py --api19-jar /path/to/android-19/android.jar --smoke --isolated-smoke
```

고정된 버전의 의존성을 내려받아 SHA-256을 검사하며, Java 단위 테스트와 APK 서명
검증을 실행한다. 도구는 `.tools/`, 중간 산출물은 `build/manual/`, APK는 `dist/`에 생성한다.
API 19 설치를 위한 v1 서명을 포함하며 ARMv7, ARM64, x86, x86_64 라이브러리를 함께 넣는다.

일반 APK는 `dist/novelpia-e-0.1.0.apk`이다. 격리 검증 APK와 smoke APK는
`dist/validation/` 아래에 생성하며 일반 앱과 다른 데이터 영역을 사용한다.

스크립트는 `.tools/debug.keystore`에 로컬 서명 키를 생성하고 재사용한다.
이 키와 비밀번호·세션 파일은 커밋하거나 릴리즈에 첨부하지 않는다.
공개 APK 업데이트에는 유지 중인 프로젝트 키를 사용한다. 다른 환경에서 새로 생성한
키로 빌드한 APK는 기존에 다른 키로 서명된 앱 위에 설치할 수 없다.

## Android Studio / Gradle

AGP 8.7.3, Gradle 8.9, JDK 17, Android SDK 35를 기준으로 한다.
Gradle wrapper는 포함하지 않았으므로 설치된 Gradle 또는 Android Studio를 사용한다.

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

## AVD 검사와 공개용 캡처

일반 앱의 실제 로그인 정보와 구분하기 위해 격리 검증 APK를 사용한다.

```sh
adb install -r dist/validation/novelpia-e-0.1.0-validation.apk
adb install -r dist/validation/novelpia-e-0.1.0-validation-smoke.apk
adb shell am instrument -w -e skipNetwork true \
  me.crema.novelia.validation.smoke/me.crema.novelia.SmokeInstrumentation
adb shell am instrument -w -e publicationScreenshots true \
  me.crema.novelia.validation.smoke/me.crema.novelia.SmokeInstrumentation
```

공개용 캡처는 실제 Activity와 대화상자의 View를 그려 저장한다. 본문은 자체 샘플,
서재는 명시적인 가상 데이터이며 계정이나 사이트를 조회하지 않는다. 결과는 앱 캐시에
`release-*.png`로 생성된다. 앱의 `FLAG_SECURE`는 해제하지 않는다.

## 소스와 외부 구성요소

릴리즈 태그에는 앱 소스와 빌드 스크립트가 포함된다. 릴리즈의 source bundle에는
같은 앱 소스와 jsoup 1.15.4의 공식 소스 아카이브를 함께 제공한다.
Conscrypt/BoringSSL의 연결 및 소스 제공 범위는 `LICENSE-EXCEPTION`을 따르며,
각 구성요소의 원래 라이선스는 그대로 적용된다. 바이너리 출처와 고지는
[THIRD_PARTY.md](THIRD_PARTY.md)에 기록한다.
