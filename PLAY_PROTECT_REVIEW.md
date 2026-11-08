# Play 프로텍트 차단 검토 자료 (Fishing)

## 사용자 보고 및 분류

- 보이는 경고: **Google Play 프로텍트 → 기기 보호를 위해 앱 차단됨**
- 설명: **이 앱에서 민감한 정보에 대한 액세스 권한을 요청할 수 있습니다. 이로 인해 신원 도용이나 금융 사기 위험이 높아질 수 있습니다.**
- 앱을 파일 관리자에서 인터넷으로 다운로드한 APK로 설치할 때 차단됨
- [Google 공식 개발자 안내](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=ko)에 명시된 **'기기 보호를 위해 앱 차단됨'** 유형과 일치
- **주의:** 소스 코드를 확인한 결과이지 악성코드가 없다고 인증하거나 Play 프로텍트 결과를 예측한 것은 아님

## 실제 코드 점검 (2026-11-08)

- 패키지 ID: `com.ddogy.fishing`
- AndroidManifest: `BIND_ACCESSIBILITY_SERVICE`가 필요한 접근성 서비스 1개와 진입 Activity 1개
- 접근성 XML: `android:packageNames="com.kakao.talk"`, `canRetrieveWindowContent=true`, `canPerformGestures=true`, `canTakeScreenshot=true`
- 접근성 XML에 `isAccessibilityTool=true`를 선언하지 않음 (장애 지원이 주목적이라는 주장 없음)
- 앱의 직접적인 인터넷 권한 선언 없음
- MainActivity: 권한과 데이터 처리 범위를 설명하고 **명시적 동의/거부 버튼**을 보여 줌
- 화면 인식: Google ML Kit 온디바이스 OCR 사용
- 사용자 제어: 앱에서 직접 시작/중지. 카카오톡 외의 패키지에서는 동작하지 않도록 검사
- 지원 기능: 사용자 지정 채팅에서 낚시 버튼을 찾아 자동 탭, 팝업 닫기, 화면 정지 시 스크롤 복구
- 한계: APK 설치 이전에 실행되는 Play 프로텍트 검사를 앱 **내부 동의 창**으로 통과시킬 수는 없음

## 공식 검토 및 이의신청

[Google Play 프로텍트 경고 관련 개발자 가이드](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=ko)의 **'Play 프로텍트 이의신청 제출'** 항목을 이용합니다.

아래 문안은 개발자가 실제 소스와 개인정보 처리 사실을 재확인하고, 사실에 맞는 경우에만 제출할 수 있는 *초안*입니다.

> Fishing is an Android utility app (`com.ddogy.fishing`) for a user-initiated, explicitly disclosed on-device automation workflow limited to the KakaoTalk package. The accessibility service reads the displayed screen and dispatches taps only after the user opts in and starts the app. The app UI contains an affirmative consent dialog and an explicit stop control. The accessibility configuration specifies `com.kakao.talk`; the code does not declare an INTERNET permission and uses on-device Korean text recognition. We understand that an internet-sideloaded APK with the Accessibility API is classified as high-risk, and we request review of whether the reported Play Protect classification is appropriate. We are not requesting users to disable or circumvent Play Protect.

참고: 검토 또는 승인 여부는 Google이 결정합니다. 접근성 권한을 유지한 채 APK 이름을 바꾸거나, 서명을 바꾸거나, 동의 창을 추가하는 것만으로 차단이 해결된다는 근거는 없습니다.

## 추가 검증 권장

- 설치된 앱의 버전과 인증서 fingerprint 확인
- 사용자가 명시적으로 시작하기 전 화면 접근/터치가 일어나지 않는지 실기기 검증
- 권한 비활성화/활성화 시 의도치 않은 재시작이 발생하지 않는지 확인
- 화면 이미지와 OCR 데이터가 파일이나 네트워크에 저장/전송되지 않는지 코드 및 패키지 검사
- 접근성 정책과 이용약관에 대한 검토



## 제출할 최신 빌드의 확인된 정보

- 앱 버전: Fishing 1.2.1 (`versionCode=4`)
- APK 파일: `Fishing-v1.2.1.apk`
- 파일 크기: 52,434,859 bytes
- SHA-256: `e9962eec94239cc504b3cb6d5faa87cbd498a272b91b285d85a9b7b7681c980e`
- 검증된 GitHub Actions 컴파일: https://github.com/ddogy1212/Fishing/actions/runs/42440013826
- 코드를 공개한 저장소: https://github.com/ddogy1212/Fishing
- 공식 차단 검토 접수: https://support.google.com/googleplay/android-developer/contact/protectappeals?hl=en

### Google에 제출 가능한 검토 요청 (정책 준수 여부는 Google이 판단)

> Hello Google Play Protect team. I am requesting a review of the installation block for Fishing, package com.ddogy.fishing, version 1.2.1. The displayed warning is "App blocked to protect your device" and states that the app may request access to sensitive information. Fishing is an explicitly user-started utility that uses Android AccessibilityService to detect a specific "낚시" button in an on-screen KakaoTalk game chat and automatically tap it, as well as perform limited recovery scrolling if the chat view stalls. The app provides a prominent disclosure and affirmative consent prompt, an explicit stop control, and restricts its accessibility-service configuration to the KakaoTalk package. Screen processing uses on-device Korean OCR. The app's own manifest does not request the INTERNET permission. We recognize the strong risk associated with sideloaded apps that use AccessibilityService and are requesting an accurate assessment, not an exception to or circumvention of device security controls. Source: https://github.com/ddogy1212/Fishing . APK SHA-256: e9962eec94239cc504b3cb6d5faa87cbd498a272b91b285d85a9b7b7681c980e .

