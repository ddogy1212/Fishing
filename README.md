# Fishing 🎣

카카오톡 방구석낚시꾼 미니게임에서 **"낚시"** 버튼만 찾는 Android용 접근성 도우미입니다.

## 현재 버전: 1.4.0 — 낚시 버튼 + 화면 복구

- 카카오톡에서 **회색 '낚시' 버튼**을 정확하게 인식해 클릭 (왼쪽/오른쪽 지원)
- 버튼이 화면에 안 보이고 '자랑하기/놓아준다' 결과 카드가 보이면 **이전 메시지를 최대 3번 탐색**해 버튼 찾기
- 이전 메시지에서 클릭한 뒤 **최신 메시지로 돌아오기**
- 버튼은 보이지만 화면 변화가 3초 이상 없으면 **짧게 위아래로 스크롤해 다시 인식**
- 카카오톡 인앱 브라우저에서 **couchfisher.com**이 확인되면 닫기 X를 찾거나 뒤로가기로 채팅 복귀
- **클릭과 스크롤은 Android 제스처 완료 콜백을 통해 순차 실행**; 취소/지연 복구
- 사용자가 앱에서 시작한 상태에만 동작; 카카오톡 화면에 한정
- 스크롤은 채팅 내에서만 시도하며 '자랑하기' 또는 '놓아준다'를 대신 누르지 않음
- 구글 Play 프로텍트가 외부 APK와 접근성 권한 사용을 이유로 차단할 수 있으므로 설치 성공은 보장하지 않음

## 작동 방식

- 사용자가 직접 시작/중지합니다. 카카오톡에서만 동작합니다.
- Google ML Kit 한국어 OCR로 글자가 정확히 '낚시'인 회색 버튼을 찾습니다.
- Android 접근성 UI 요소는 OCR 실패 시 보조 인식에만 활용합니다.
- 버튼이 없는 결과 카드가 뜨면 실제로 위쪽에 있는 과거 메시지를 탐색합니다. 손가락을 아래 방향으로 쓸어 이전 메시지를 보여주고, 최대 3회 탐색합니다.
- 버튼을 발견해 클릭하면 같은 횟수만큼 최근 메시지 방향으로 스크롤해 복귀합니다.
- 3회 탐색해도 찾지 못하면 최신 화면으로 돌아온 뒤 주기적으로 재검사합니다.
- couchfisher.com 페이지로 이동한 것이 명확히 확인되면 해당 창만 닫습니다.
- 어떤 대상도 확실하지 않으면 추측으로 클릭하지 않습니다.

## APK 빌드 및 다운로드

GitHub Actions의 [Build Android APK](https://github.com/ddogy1212/Fishing/actions/workflows/android.yml) 워크플로가 main 브랜치의 푸시마다 자동 빌드합니다.

1. 저장소의 **Actions** 탭을 엽니다.
2. 가장 최근 **Build Android APK** 실행을 열고 녹색 체크(성공)를 확인합니다.
3. **Artifacts**에서 `Fishing-debug-apk` ZIP을 다운로드합니다.
4. ZIP 압축을 풀어 `app-debug.apk` 파일을 안드로이드에 설치합니다.

실행 기록이 없다면 **Run workflow** 버튼으로 수동 실행할 수 있습니다. GitHub 로그인 권한이 필요할 수 있습니다.

## 사용법

1. Android 11(API 30) 이상의 휴대폰에 APK를 설치합니다.
2. 앱을 열고 **접근성 설정 열기**를 누릅니다.
3. 설정에서 **Fishing 도우미** 서비스를 허용합니다.
4. 앱으로 돌아와 **자동 낚시 시작**을 누르고 카카오톡의 낚시 채팅방으로 이동합니다.
5. 종료하려면 Fishing 앱으로 돌아와 **자동 낚시 중지**를 누르거나 접근성 서비스를 끕니다.

## 개인정보와 주의사항

- 화면 인식은 기기 내부에서만 수행하며 원격 OCR API로 전송하지 않습니다.
- 앱에는 인터넷 권한을 선언하지 않았습니다.
- 접근성 권한은 화면 캡처와 터치를 수행할 수 있는 강력한 권한입니다. 신뢰하는 APK에만 부여하세요.
- 별도의 실기기 테스트 전이므로 **모든 화면/팝업에서의 인식 정확도를 보장하지 않습니다**.
- 게임 이용약관에서 자동화가 허용되는지 확인하세요.

## 개발

Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17, compileSdk 35, ML Kit Korean OCR 16.0.1. Android Studio에서 열거나 `gradle assembleDebug`를 실행해 로컬 빌드할 수 있습니다.

## 빌드 문제 해결

Actions에 실패한 실행이 있다면 실행 → **build** → 실패 단계의 로그를 확인하세요. ML Kit 라이브러리 첫 다운로드에 시간이 걸릴 수 있습니다.

## 안정적인 릴리스 서명 (2026-11-08 추가)

이전 GitHub 빌드는 매번 새 가상 머신의 **임시 debug 키**로 서명했습니다. 그래서 다운로드된 APK마다 서명이 달라질 수 있습니다.
Android 업데이트는 **동일한 패키지 ID와 서명 인증서**가 필요합니다. 서로 다른 인증서로 서명된 1.0~1.2 디버그 APK와 새 release APK는 일반적으로 덮어쓸 수 없습니다.

앞으로 같은 인증서로 업데이트를 빌드하기 위해 **Signed Android release (stable certificate)** 워크플로를 별도로 추가했습니다.
이 워크플로는 개인키를 GitHub 코드에 저장하지 않고, 사용자가 GitHub Actions Secrets에 등록한 키로만 서명합니다.

### 최초 한 번만 필요한 설정

1. 안전하게 보관할 개인 서명 키를 Android Studio의 **Build > Generate Signed Bundle / APK**에서 만들거나, 본인 PC의 `keytool`로 만듭니다. 예: `keytool -genkeypair -v -keystore fishing-release.jks -storetype PKCS12 -keyalg RSA -keysize 3072 -validity 10000 -alias fishing-release`.
2. **서명 키 파일과 비밀번호를 반드시 별도로 백업**하고, GitHub 소스 코드에 올리지 마세요.
3. 키 파일을 base64 단일 문자열로 변환합니다. 예: Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("fishing-release.jks"))` / Linux: `base64 -w 0 fishing-release.jks`.
4. GitHub 저장소 **Settings → Secrets and variables → Actions → New repository secret**에서 아래 값을 등록합니다.
   - `FISHING_KEYSTORE_BASE64`: 키 파일의 base64 문자열
   - `FISHING_STORE_PASSWORD`: 키 저장소 비밀번호
   - `FISHING_KEY_ALIAS`: 키 별칭 (예: fishing-release)
   - `FISHING_KEY_PASSWORD`: 별칭의 키 비밀번호 (PKCS12에서는 저장소 비밀번호와 같을 수 있음)
5. [Signed Android release 워크플로](https://github.com/ddogy1212/Fishing/actions/workflows/release.yml)를 열어 **Run workflow**를 실행합니다.
6. 작업이 초록색으로 완료되면 **Fishing-signed-release-apk** 아티팩트에서 `app-release.apk`를 내려받습니다.
7. 매번 **같은 키와 비밀번호**를 유지하면 향후 릴리스의 서명을 일관되게 유지할 수 있습니다.

주의: 아직 서명용 GitHub Secrets가 설정되어 있지 않으면 이 워크플로는 명시적으로 실패하며, 서명 APK가 생성되지 않습니다. 이 기능을 위해 새 키를 생성하면 기존 임시 debug 서명 APK와 **서명이 달라집니다**. 기존 앱이 설치되어 있으면 덮어쓰기가 거부될 수 있습니다.

### Play 프로텍트와는 별개의 문제

공식 안내: https://developers.google.com/android/play-protect/warning-dev-guidance

서명 고정은 앱 **업데이트 무결성** 문제를 해결하기 위한 조치이지, Google Play 프로텍트 설치 차단을 우회하거나 예외 처리하지 않습니다. 인터넷에서 받은 접근성 사용 앱은 별도 차단 대상이 될 수 있습니다. 차단되면 Play 프로텍트를 끄거나 검사 없이 설치하지 말고, 공식 개발자 재검토 절차를 사용하세요.

