package com.ddogy.fishing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.ComponentName;
import android.provider.Settings;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

/**
 * User-controlled, on-device fishing button assistant for KakaoTalk.
 * Recovers from fishing-result cards by searching older chat content,
 * then returns to latest messages. Closes only positively identified
 * couchfisher.com in-app browsers; never presses result-choice buttons.
 */
public class FishingAccessibilityService extends AccessibilityService {
    public static volatile FishingAccessibilityService active;

    private static final String KAKAO = "com.kakao.talk";
    private static final String PREFS = "fishing_settings";
    private static final String USER_STARTED = "user_requested_running";
    private static final int OCR_TIMEOUT_MS = 9000;
    private static final int GESTURE_TIMEOUT_MS = 4000;
    private static final long STALL_MS = 3000;
    private static final long RETRY_OLDER_COOLDOWN_MS = 9000;
    private static final long SESSION_WATCHDOG_MS = 12500;
    private static final long HEARTBEAT_INTERVAL_MS = 2500;
    private static final int MIN_FISH_TAP_INTERVAL_MS = 1800;
    private static final int MAX_FISH_TAP_INTERVAL_MS = 2200;
    // A view must have been reached directly after an observed Kakao chat.
    private static final long TRUSTED_VIEW_WINDOW_MS = 20000;
    private static final long EXIT_RETRY_COOLDOWN_MS = 1800;
    private static final int MAX_EXIT_ATTEMPTS_PER_VIEW = 3;
    private static final int MAX_OLDER_SWIPES = 3;
    private static final int MAX_REVEAL_SWIPES = 2;
    private static final long REVEAL_RETRY_MS = 2200;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable ticker = this::scanOnce;

    private TextRecognizer recognizer;
    private boolean running;
    private boolean scanning;
    private boolean tapping;
    private int scanToken;
    private int gestureToken;
    private long scans;
    private long clicks;
    private long failures;
    private long missed;
    private long cancelledGestures;
    private long recoveryCount;
    private long popupsClosed;
    private long lastFishingTapAt;
    private long lastFishingDispatchAt;
    private long nextFishingTapEligibleAt;
    private long lastChosenTapIntervalMs;
    private long lastChatSeenAt;
    private long lastExitAttemptAt = -EXIT_RETRY_COOLDOWN_MS;
    private int viewExitAttempts;
    private long rescuedPhotoViews;
    private long rescuedWebViews;
    private long revealedBottomButtons;
    private long blockedTextCandidates;
    private long visualPairFallbacks;
    private long mergedOcrPairs;
    private long lastRevealAt = -REVEAL_RETRY_MS;
    private int revealSwipes;
    private long noButtonSince;
    private long lastSeekAt = -RETRY_OLDER_COOLDOWN_MS;
    private long lastVisualChangeAt;
    private int[] lastVisualSample;
    private int olderSwipes;
    private boolean returningToLatest;
    private int sessionId;
    private long lastProgressAt;
    private long watchdogRecoveries;
    private String lastRecoveryReason = "없음";
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    private String status = "준비 완료";

    /**
     * Unlike scanTimeout/tapTimeout, this is independent of the current
     * operation. An early gesture precondition failure may leave
     * returningToLatest=true with NO pending work. Always recover.
     */
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (lastProgressAt == 0) lastProgressAt = now;
            if (now - lastProgressAt >= SESSION_WATCHDOG_MS) {
                watchdogRecoveries++;
                lastRecoveryReason = returningToLatest
                    ? "스크롤 복귀 상태가 종료되지 않음"
                    : (scanning ? "화면 인식 중단"
                    : (tapping ? "제스처 응답 중단" : "다음 탐색 예약 누락"));
                // Invalidate stale screenshot/OCR/gesture callbacks.
                scanToken++;
                gestureToken++;
                sessionId++;
                scanning = false;
                tapping = false;
                returningToLatest = false;
                olderSwipes = 0;
                noButtonSince = 0;
                lastVisualSample = null;
                lastProgressAt = now;
                main.removeCallbacks(scanTimeout);
                main.removeCallbacks(tapTimeout);
                recordEvent("독립 복구: " + lastRecoveryReason);
                status = "멈춤 감지 → 자동 재탐색 (" + watchdogRecoveries + "회)";
                schedule(800);
            }
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void recordProgress() {
        lastProgressAt = SystemClock.uptimeMillis();
    }

    private void recordEvent(String value) {
        if (recentEvents.size() >= 6) recentEvents.removeFirst();
        recentEvents.addLast(value);
    }

    public String getRecentEvents() {
        StringBuilder out = new StringBuilder();
        for (String event : recentEvents) out.append("• ").append(event).append("\n");
        return out.toString();
    }

    public long getLastProgressAgeSeconds() {
        return lastProgressAt == 0 ? 0
            : Math.max(0, (SystemClock.uptimeMillis() - lastProgressAt) / 1000);
    }

    public long getWatchdogRecoveries() { return watchdogRecoveries; }
    public String getLastRecoveryReason() { return lastRecoveryReason; }
    public long getLastChosenTapIntervalMs() { return lastChosenTapIntervalMs; }
    public long getRescuedPhotoViews() { return rescuedPhotoViews; }
    public long getRescuedWebViews() { return rescuedWebViews; }
    public long getRevealedBottomButtons() { return revealedBottomButtons; }
    public long getBlockedTextCandidates() { return blockedTextCandidates; }
    public long getVisualPairFallbacks() { return visualPairFallbacks; }
    public long getMergedOcrPairs() { return mergedOcrPairs; }

    /**
     * Detect older installs of THIS project that may run a second
     * AccessibilityService and cancel our gestures. Does not disable or
     * manipulate Android accessibility settings.
     */
    public String getConflictingService() {
        String enabled;
        try {
            enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (SecurityException e) {
            // Some managed devices restrict reading enabled services.
            // Never crash a fishing session because of this optional check.
            return "";
        }
        if (enabled == null || enabled.isEmpty()) return "";
        for (String item : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(item);
            if (component == null) continue;
            String pkg = component.getPackageName();
            String clazz = component.getClassName();
            if (pkg != null && pkg.startsWith("com.ddogy.fishing")
                && !getPackageName().equals(pkg)
                && clazz != null
                && clazz.endsWith(".FishingAccessibilityService")) {
                return pkg;
            }
        }
        return "";
    }

    private final Runnable scanTimeout = () -> {
        if (!running || !scanning) return;
        scanToken++;
        scanning = false;
        failures++;
        status = "화면 인식 지연 → 다시 검사";
        schedule(1100);
    };

    private final Runnable tapTimeout = () -> {
        if (!running || !tapping) return;
        gestureToken++;
        tapping = false;
        cancelledGestures++;
        returningToLatest = false;
        status = "제스처 응답 지연 → 다시 검사";
        schedule(1200);
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        recognizer = TextRecognition.getClient(
            new KoreanTextRecognizerOptions.Builder().build());
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(USER_STARTED, false)) {
            if (!getConflictingService().isEmpty()) {
                running = false;
                status = "이전 Fishing 접근성 서비스가 켜져 있어 자동 실행을 중지했어요";
                return;
            }
            running = true;
            recordProgress();
            main.removeCallbacks(heartbeat);
            main.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
            status = "이전 실행 세션 복원 중";
            schedule(600);
        } else {
            status = "시작 버튼을 눌러 주세요";
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Never trigger an extra tap just because KakaoTalk sends an event.
    }

    @Override public void onInterrupt() {
        if (running && !scanning && !tapping && !returningToLatest) schedule(900);
    }

    @Override public void onDestroy() {
        stopRunning();
        if (recognizer != null) recognizer.close();
        if (active == this) active = null;
        super.onDestroy();
    }

    public void startRunning() {
        if (running) return;
        String conflicting = getConflictingService();
        if (!conflicting.isEmpty()) {
            status = "기존 Fishing 도우미 접근성 서비스를 먼저 꺼 주세요: " + conflicting;
            recordEvent("중복 Fishing 서비스 발견: " + conflicting);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(USER_STARTED, true).apply();
        running = true;
        scanning = false;
        tapping = false;
        scanToken++;
        gestureToken++;
        sessionId++;
        olderSwipes = 0;
        returningToLatest = false;
        noButtonSince = 0;
        lastVisualChangeAt = 0;
        lastVisualSample = null;
        lastSeekAt = -RETRY_OLDER_COOLDOWN_MS;
        nextFishingTapEligibleAt = 0;
        lastFishingDispatchAt = 0;
        lastChosenTapIntervalMs = 0;
        lastChatSeenAt = 0;
        viewExitAttempts = 0;
        lastExitAttemptAt = -EXIT_RETRY_COOLDOWN_MS;
        revealSwipes = 0;
        lastRevealAt = -REVEAL_RETRY_MS;
        recordProgress();
        main.removeCallbacks(heartbeat);
        main.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
        recordEvent("사용자 시작");
        status = "카카오톡 낚시 채팅방으로 이동하세요";
        schedule(450);
    }

    public void stopRunning() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(USER_STARTED, false).apply();
        running = false;
        scanning = false;
        tapping = false;
        scanToken++;
        gestureToken++;
        sessionId++;
        olderSwipes = 0;
        returningToLatest = false;
        noButtonSince = 0;
        lastVisualSample = null;
        lastChatSeenAt = 0;
        viewExitAttempts = 0;
        revealSwipes = 0;
        main.removeCallbacks(ticker);
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(tapTimeout);
        main.removeCallbacks(heartbeat);
        recordEvent("사용자 중지");
        status = "중지됨";
    }

    public boolean isRunning() { return running; }
    public long getClickCount() { return clicks; }
    public long getScans() { return scans; }
    public long getTimeouts() { return failures; }
    public long getCaptureFailures() { return failures; }
    public long getUnmatchedScans() { return missed; }
    public long getRecoveryCount() { return recoveryCount; }
    public long getPopupsClosed() { return popupsClosed; }
    public long getCancelledGestures() { return cancelledGestures; }
    public String getCurrentStatus() { return status; }

    private void schedule(long delayMs) {
        main.removeCallbacks(ticker);
        if (running && !scanning && !tapping && !returningToLatest) {
            // A retry can never shorten the random tap spacing.
            // Scan OCR again when the next tap becomes eligible.
            long remaining = nextFishingTapEligibleAt - SystemClock.uptimeMillis();
            main.postDelayed(ticker, Math.max(delayMs, Math.max(0, remaining)));
        }
    }

    private String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    private boolean isTrustedGalleryPackage(String packageName) {
        // Samsung Gallery and Google Photos are only supported if they
        // became foreground immediately after a verified Kakao chat.
        return "com.sec.android.gallery3d".equals(packageName)
            || "com.samsung.android.gallery3d".equals(packageName)
            || "com.google.android.apps.photos".equals(packageName);
    }

    private boolean recentlyInKakaoChat() {
        long now = SystemClock.uptimeMillis();
        return lastChatSeenAt > 0
            && now - lastChatSeenAt <= TRUSTED_VIEW_WINDOW_MS;
    }

    /** Exit a known viewer without touching arbitrary app content. */
    private boolean goBackFromTrustedView(String reason) {
        if (!running || !recentlyInKakaoChat() || tapping) return false;
        long now = SystemClock.uptimeMillis();
        if (viewExitAttempts >= MAX_EXIT_ATTEMPTS_PER_VIEW) {
            status = "복귀 3회 실패 → 잘못된 화면 조작 방지를 위해 중지";
            return false;
        }
        if (now - lastExitAttemptAt < EXIT_RETRY_COOLDOWN_MS) {
            status = reason + " → 복귀 결과 확인 중";
            return false;
        }
        lastExitAttemptAt = now;
        viewExitAttempts++;
        // Global BACK (the user's requested back button) is safer than
        // guessing an unlabeled picture, menu or ad close coordinate.
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        if (!ok) ok = clickKnownUpperLeftBack();
        if (ok) {
            if (reason.contains("사진")) rescuedPhotoViews++;
            else rescuedWebViews++;
            recordEvent("복귀 시도: " + reason + " (" + viewExitAttempts + ")");
            status = reason + " → 뒤로가기로 채팅 복귀 확인";
            olderSwipes = 0;
            returningToLatest = false;
            noButtonSince = 0;
            lastVisualSample = null;
            recordProgress();
        } else {
            status = reason + " → 뒤로가기 실패, 확인 필요";
        }
        return ok;
    }

    /**
     * Only click a top-left Back/Close accessibility node in a trusted
     * viewer, and only if Android's normal BACK action was rejected.
     */
    private boolean clickKnownUpperLeftBack() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        int count = 0;
        while (!nodes.isEmpty() && count++ < 500) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            String label = ((node.getContentDescription() == null ? ""
                : node.getContentDescription().toString()) + " "
                + (node.getText() == null ? "" : node.getText().toString()))
                .trim().toLowerCase(java.util.Locale.ROOT);
            boolean expected = label.equals("뒤로") || label.equals("뒤로 가기")
                || label.equals("이전") || label.equals("닫기")
                || label.equals("navigate up") || label.equals("back")
                || label.equals("close");
            if (expected && node.isVisibleToUser()
                && r.centerX() > w * .015 && r.centerX() < w * .22
                && r.centerY() > h * .025 && r.centerY() < h * .19
                && node.isClickable() && node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.add(child);
            }
        }
        return false;
    }

    private void observedChat() {
        lastChatSeenAt = SystemClock.uptimeMillis();
        if (viewExitAttempts > 0) {
            viewExitAttempts = 0;
            recordEvent("채팅 복귀 확인");
        }
    }

    private boolean onKakao() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null
            && KAKAO.contentEquals(root.getPackageName());
    }

    private boolean visibleKakaoChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
            || !KAKAO.contentEquals(root.getPackageName())) return false;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence label = node.getText();
            CharSequence desc = node.getContentDescription();
            if (node.isEditable()
                || (label != null && label.toString().contains("메시지 입력"))
                || (desc != null && desc.toString().contains("메시지 입력"))) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return false;
    }

    private void scanOnce() {
        if (!running || scanning || tapping || returningToLatest) return;
        recordProgress();
        if (!getConflictingService().isEmpty()) {
            status = "다른 Fishing 앱의 접근성 서비스와 중복됨 → 중지";
            recordEvent("중복 접근성 자동화 감지");
            stopRunning();
            return;
        }
        String currentPackage = foregroundPackage();
        if (!KAKAO.equals(currentPackage)) {
            if (isTrustedGalleryPackage(currentPackage) && recentlyInKakaoChat()) {
                goBackFromTrustedView("외부 사진 뷰어");
            } else {
                status = "카카오톡 화면 대기 중";
            }
            schedule(900);
            return;
        }
        // Only count an explicitly visible Kakao chat composer as a
        // verified return. A hidden composer behind a modal is not enough.
        if (hasKakaoChatComposer()) observedChat();

        scanning = true;
        scans++;
        final int id = ++scanToken;
        main.removeCallbacks(scanTimeout);
        main.postDelayed(scanTimeout, OCR_TIMEOUT_MS);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        HardwareBuffer buffer = screenshot.getHardwareBuffer();
                        Bitmap copy = null;
                        try {
                            if (!running || id != scanToken) return;
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(
                                buffer, screenshot.getColorSpace());
                            if (hardware != null) {
                                copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                hardware.recycle();
                            }
                        } catch (Exception error) {
                            status = "화면 복사 실패 → 다시 검사";
                        } finally {
                            if (buffer != null) buffer.close();
                        }
                        if (copy == null) {
                            if (running && id == scanToken) {
                                failures++;
                                finishScan(id, 1200);
                            }
                            return;
                        }
                        recognize(copy, id);
                    }

                    @Override public void onFailure(int errorCode) {
                        if (!running || id != scanToken) return;
                        failures++;
                        status = "화면 캡처 실패 (" + errorCode + ")";
                        finishScan(id, 1200);
                    }
                });
        } catch (Exception e) {
            failures++;
            status = "화면 인식 예외 → 재시도";
            finishScan(id, 1200);
        }
    }

    private void recognize(Bitmap image, int id) {
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(image, 0));
        } catch (Exception error) {
            image.recycle();
            failures++;
            finishScan(id, 1200);
            return;
        }

        task.addOnSuccessListener(text -> {
            if (!running || id != scanToken) return;
            boolean inChat = visibleKakaoChat();
            // Page identity takes precedence: an in-app web search field can
            // appear editable and otherwise look like a chat composer.
            if (isFishingWebsite(text, image.getHeight()) && !hasKakaoChatComposer()) {
                status = "낚시 웹페이지 감지 → 닫기/뒤로가기";
                closeFishingWebsite(text, image);
                return;
            }
            // The Kakao photo viewer can expose old chat edit fields
            // behind its overlay. Positive viewer evidence takes priority.
            if (isKakaoPhotoViewer(text, image.getHeight())) {
                goBackFromTrustedView("카카오톡 사진 보기");
                return;
            }
            if (!inChat) {
                status = "채팅이 아닌 화면 감지 → 안전 대기";
                return;
            }
            if (hasKakaoChatComposer()) observedChat();

            recordVisualFrame(image);
            int composerTop = findComposerTop(image.getHeight());
            // Always consider BOTH sources. If two fishing buttons are
            // present, the lowest visible one is the newest message.
            Target ocrTarget = findFishing(text, image, composerTop);
            Target nodeTarget = findFishingFromNodes(image, composerTop);
            Target target;
            if (ocrTarget == null) target = nodeTarget;
            else if (nodeTarget == null) target = ocrTarget;
            else target = ocrTarget.y >= nodeTarget.y ? ocrTarget : nodeTarget;
            long now = SystemClock.uptimeMillis();

            if (target != null) {
                // A visible, verified fishing button ALWAYS wins over refresh
                // or scrolling logic. The old 1.4 branch was scrolling
                // even with a visible button; that caused an endless loop.
                noButtonSince = 0;
                revealSwipes = 0;
                long remainingMs = nextFishingTapEligibleAt - now;
                if (remainingMs > 0) {
                    status = "낚시 버튼 발견 · 최소 터치 간격 대기 " + remainingMs + "ms";
                    return;
                }
                status = "낚시 버튼 확인 (" + target.side + ") → 터치";
                tap(target.x, target.y);
                return;
            }

            missed++;
            if (noButtonSince == 0) noButtonSince = now;

            // A result card is visible but its action row is underneath
            // Kakao's fixed message composer. Scrolling to OLDER messages
            // would make it worse: swipe UP (toward newer content) first.
            if (shouldRevealBottomCard(text, image, composerTop)) {
                if (revealSwipes < MAX_REVEAL_SWIPES
                    && now - lastRevealAt >= REVEAL_RETRY_MS) {
                    revealBottomCard(image.getWidth(), image.getHeight(), composerTop);
                } else {
                    status = "하단 버튼이 입력창에 가려짐 → 재확인 중";
                }
                return;
            }
            boolean resultCard = hasResultChoices(text);
            boolean shouldSeek = resultCard || olderSwipes > 0
                || now - noButtonSince >= STALL_MS;
            if (shouldSeek && (olderSwipes > 0
                || now - lastSeekAt >= RETRY_OLDER_COOLDOWN_MS)) {
                searchOlderButton(image.getWidth(), image.getHeight());
            } else {
                status = resultCard
                    ? "결과 카드 발견 → 이전 낚시 버튼 탐색 준비"
                    : "낚시 버튼 미표시 → 화면 재검사";
            }
        }).addOnFailureListener(error -> {
            if (running && id == scanToken) {
                failures++;
                status = "한글 인식 실패 → 재시도";
            }
        }).addOnCompleteListener(t -> {
            if (!image.isRecycled()) image.recycle();
            finishScan(id, 850);
        });
    }

    private void finishScan(int id, long delayMs) {
        if (id != scanToken) return;
        scanning = false;
        main.removeCallbacks(scanTimeout);
        recordProgress();
        schedule(delayMs);
    }


    /** A small color fingerprint; screenshots are never stored. */
    private boolean recordVisualFrame(Bitmap image) {
        int[] sample = new int[12 * 10];
        int k = 0;
        int w = image.getWidth(), h = image.getHeight();
        for (int iy = 0; iy < 12; iy++) {
            int y = (int)(h * (.23 + .57 * (iy + .5) / 12));
            for (int ix = 0; ix < 10; ix++) {
                int x = (int)(w * (.12 + .76 * (ix + .5) / 10));
                sample[k++] = image.getPixel(x, y) & 0xffffff;
            }
        }
        long now = SystemClock.uptimeMillis();
        int changes = 0;
        if (lastVisualSample != null) {
            for (int i = 0; i < sample.length; i++) {
                int a = sample[i], b = lastVisualSample[i];
                int delta = (Math.abs(Color.red(a) - Color.red(b))
                    + Math.abs(Color.green(a) - Color.green(b))
                    + Math.abs(Color.blue(a) - Color.blue(b))) / 3;
                if (delta >= 22) changes++;
            }
        }
        if (lastVisualSample == null || changes >= 4 || lastVisualChangeAt == 0)
            lastVisualChangeAt = now;
        lastVisualSample = sample;
        return now - lastVisualChangeAt >= STALL_MS;
    }

    /** Fishing result cards often have no fishing action, only two choices. */
    private boolean hasResultChoices(Text recognized) {
        boolean brag = false, release = false;
        for (Text.TextBlock b : recognized.getTextBlocks()) {
            String s = b.getText();
            if (s.contains("자랑하기")) brag = true;
            if (s.contains("놓아준다")) release = true;
        }
        return brag && release;
    }

    /** Strong signal from the photo viewer screenshot, not arbitrary text. */
    private boolean isKakaoPhotoViewer(Text recognized, int screenHeight) {
        StringBuilder all = new StringBuilder();
        boolean topPhotoAction = false;
        for (Text.TextBlock b : recognized.getTextBlocks()) {
            String normalized = b.getText().replace(" ", "");
            all.append(normalized).append(" ");
            Rect bounds = b.getBoundingBox();
            if (bounds != null && bounds.centerY() < screenHeight * .30
                && normalized.contains("AI로만들기")) topPhotoAction = true;
        }
        String txt = all.toString();
        return topPhotoAction && (txt.contains("오후") || txt.contains("오전")
            || txt.contains("삭제") || txt.contains("공유")
            || txt.contains("다운로드"));
    }

    /** Avoid interpreting a webpage text input as a Kakao message composer. */
    private boolean hasKakaoChatComposer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
            || !KAKAO.contentEquals(root.getPackageName())) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count++ < 500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence label = node.getText();
            CharSequence desc = node.getContentDescription();
            if (node.isVisibleToUser() &&
                ((label != null && label.toString().contains("메시지 입력"))
                || (desc != null && desc.toString().contains("메시지 입력"))))
                return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return false;
    }

    private boolean isFishingWebsite(Text recognized, int screenHeight) {
        for (Text.TextBlock b : recognized.getTextBlocks()) {
            String s = b.getText().toLowerCase(java.util.Locale.ROOT);
            Rect r = b.getBoundingBox();
            boolean inBrowserHeader = r != null
                && r.centerY() < screenHeight * .18;
            if ((inBrowserHeader && s.contains("couchfisher.com"))
                || s.contains("카카오톡으로 돌아가면 낚시를 계속")
                || s.contains("낚시를 계속할 수 있어요")) return true;
        }
        return false;
    }

    /** Only operate on a positively recognized fishing website inside Kakao. */
    private void closeFishingWebsite(Text text, Bitmap image) {
        if (!running || !onKakao()) return;
        if (!recentlyInKakaoChat()) {
            status = "웹 복귀: 최근 채팅 기록 없음 → 안전 대기";
            return;
        }
        // The top-level Kakao in-app browser X is preferred over an
        // inside-page popup X, which only closes the internal popup.
        Target close = findWebCloseFromNodes(image.getWidth(), image.getHeight());
        if (close == null) close = findWebCloseFromOcr(text, image);
        if (close == null) close = findWebClosePixels(image);
        if (close != null && viewExitAttempts == 0) {
            viewExitAttempts++;
            lastExitAttemptAt = SystemClock.uptimeMillis();
            final int x = close.x, y = close.y;
            boolean sent = dispatchAction(x, y, x, y, 80, Action.CLOSE, null);
            if (!sent) goBackFromTrustedView("낚시 웹페이지");
        } else {
            goBackFromTrustedView("낚시 웹페이지");
        }
    }

    private Target findWebCloseFromNodes(int w, int h) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            String s = ((node.getText() == null ? "" : node.getText().toString())
                + " " + (node.getContentDescription() == null
                ? "" : node.getContentDescription().toString())).trim().toLowerCase(
                    java.util.Locale.ROOT);
            if (node.isVisibleToUser() && r.width() > 8 && r.height() > 8
                && r.centerX() < w * .23 && r.centerX() > w * .015
                && r.centerY() > h * .03 && r.centerY() < h * .16
                && (s.equals("x") || s.equals("×") || s.equals("✕")
                    || s.equals("닫기") || s.contains("닫기 버튼")
                    || s.equals("close") || s.equals("close button"))) {
                return new Target(r.centerX(), r.centerY(), "웹 닫기");
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private Target findWebCloseFromOcr(Text recognized, Bitmap image) {
        int w = image.getWidth(), h = image.getHeight();
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element e : line.getElements()) {
                    Rect r = e.getBoundingBox();
                    if (r == null) continue;
                    String s = e.getText().trim();
                    if (r.centerX() > w * .015 && r.centerX() < w * .23
                        && r.centerY() > h * .03 && r.centerY() < h * .16
                        && (s.equals("X") || s.equals("×") || s.equals("✕")
                            || s.equals("닫기"))) {
                        return new Target(r.centerX(), r.centerY(), "웹 닫기");
                    }
                }
            }
        }
        return null;
    }

    /** Graphic X detection restricted to the top-left web toolbar. */
    private Target findWebClosePixels(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int bestScore = 0, bestX = -1, bestY = -1;
        int fromX = Math.max(16, Math.round(w * .025f));
        int toX = Math.min(Math.round(w * .18f), 180);
        int fromY = Math.max(35, Math.round(h * .042f));
        int toY = Math.round(h * .145f);
        for (int y = fromY; y < toY; y += 3) {
            for (int x = fromX; x < toX; x += 3) {
                int score = 0;
                for (int r : new int[]{5, 8, 11}) {
                    if (x-r<0 || y-r<0 || x+r>=w || y+r>=h) continue;
                    int diagonal = (luma(bitmap, x-r, y-r)
                        + luma(bitmap, x+r, y+r)
                        + luma(bitmap, x-r, y+r)
                        + luma(bitmap, x+r, y-r)) / 4;
                    int cardinal = (luma(bitmap, x-r, y)
                        + luma(bitmap, x+r, y)
                        + luma(bitmap, x, y-r)
                        + luma(bitmap, x, y+r)) / 4;
                    if (diagonal >= 145 && diagonal - cardinal >= 55) score++;
                }
                if (score > bestScore) {
                    bestScore = score; bestX = x; bestY = y;
                }
            }
        }
        return bestScore >= 3 ? new Target(bestX, bestY, "그래픽 X") : null;
    }

    private int luma(Bitmap b, int x, int y) {
        int c = b.getPixel(x,y);
        return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
    }

    /**
     * Older Kakao messages are ABOVE the current result. Dragging the finger
     * DOWN reveals older messages. We cap the search at three steps and
     * reverse the same number of steps to return to recent messages.
     */
    private void searchOlderButton(int w, int h) {
        if (!running || tapping || returningToLatest || !visibleKakaoChat())
            return;
        if (olderSwipes >= MAX_OLDER_SWIPES) {
            // Reaching the search limit does NOT mean another quick round
            // trip. Back off before restarting to avoid a bounce loop.
            lastSeekAt = SystemClock.uptimeMillis();
            returnToLatest(olderSwipes);
            return;
        }
        lastSeekAt = SystemClock.uptimeMillis();
        recordProgress();
        status = "낚시 버튼 없음 → 이전 메시지 탐색 ("
            + (olderSwipes + 1) + "/" + MAX_OLDER_SWIPES + ")";
        final int x = Math.round(w * .88f);
        final int upper = Math.round(h * .38f);
        final int lower = Math.round(h * .73f);
        dispatchAction(x, upper, x, lower, 280, Action.SCROLL, () -> {
            olderSwipes++;
            recoveryCount++;
            lastVisualSample = null;
            status = "이전 메시지 표시 → 낚시 버튼 다시 검색";
            schedule(580);
        });
    }

    private void returnToLatest(int steps) {
        if (!running || returningToLatest || steps <= 0) return;
        returningToLatest = true;
        olderSwipes = 0;
        recordProgress();
        status = "최신 메시지로 복귀";
        doReturnStep(steps, sessionId);
    }

    private void doReturnStep(int remaining, int session) {
        if (!running || session != sessionId) {
            if (running) {
                returningToLatest = false;
                schedule(950);
            }
            return;
        }
        recordProgress();
        if (remaining <= 0) {
            returningToLatest = false;
            noButtonSince = SystemClock.uptimeMillis();
            lastSeekAt = noButtonSince;
            lastVisualSample = null;
            status = "최신 메시지 복귀 → 잠시 대기 후 재탐색";
            schedule(850);
            return;
        }
        if (!visibleKakaoChat()) {
            returningToLatest = false;
            schedule(950);
            return;
        }
        // No second accessibility root lookup here: Android may detach the
        // window between checks, causing a NullPointerException. The guarded
        // dispatch method checks the foreground chat again.
        // Gesture endpoints remain inside the chat region.
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int x = Math.round(w * .88f);
        int upper = Math.round(h * .38f);
        int lower = Math.round(h * .73f);
        boolean dispatched = dispatchAction(x, lower, x, upper, 280,
            Action.SCROLL, () -> main.postDelayed(
                () -> doReturnStep(remaining - 1, session), 300));
        if (!dispatched) {
            returningToLatest = false;
            olderSwipes = 0;
            status = "스크롤 복귀 실패 → 탐색 상태 재설정";
            recordEvent("복귀 제스처 미실행");
            schedule(1100);
        }
    }

    /**
     * The Kakao message input is an overlay above the message list.
     * Even if OCR reads a fishing label THROUGH it, the coordinate cannot
     * be tapped safely. Fall back conservatively if the accessibility
     * bounds are not available.
     */
    private int findComposerTop(int screenHeight) {
        int fallback = (int) (screenHeight * .866);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return fallback;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0, best = screenHeight;
        while (!queue.isEmpty() && visited++ < 650) {
            AccessibilityNodeInfo n = queue.removeFirst();
            CharSequence txt = n.getText();
            CharSequence desc = n.getContentDescription();
            boolean composer = n.isEditable()
                || (txt != null && txt.toString().contains("메시지 입력"))
                || (desc != null && desc.toString().contains("메시지 입력"));
            if (composer) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.top > screenHeight * .66 && r.top < screenHeight * .98) {
                    // The input field sits inside a thicker, floating toolbar.
                    best = Math.min(best, r.top - Math.max(12,
                        (int)(screenHeight * .015)));
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return best == screenHeight ? fallback : Math.min(fallback, best);
    }

    private boolean withinTouchableChat(Rect rect, int width, int height,
            int composerTop) {
        if (rect == null || rect.width() < 7 || rect.height() < 7)
            return false;
        // Reject notification banners, the message composer and system nav.
        int margin = Math.max(12, (int)(height * .01));
        return rect.left >= width * .10 && rect.right <= width * .91
            && rect.top >= height * .16
            && rect.bottom < composerTop - margin
            && rect.height() <= height * .10
            && rect.width() <= width * .50;
    }

    private boolean isCompanion(String value) {
        String cleaned = value.replaceAll("[^가-힣]", "");
        return "대결".equals(cleaned) || "강화".equals(cleaned)
            || "자랑하기".equals(cleaned);
    }

    private boolean sameButtonRow(Rect fish, Rect other, int w, int h) {
        return fish != null && other != null
            && Math.abs(fish.centerY() - other.centerY())
                <= Math.max(26, (int)(h * .031))
            && Math.abs(fish.centerX() - other.centerX()) > w * .10
            && Math.abs(fish.centerX() - other.centerX()) < w * .72;
    }

    private Target findFishing(Text result, Bitmap image, int composerTop) {
        ArrayList<Rect> fish = new ArrayList<>();
        ArrayList<Rect> companions = new ArrayList<>();
        ArrayList<Rect> mergedRows = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineWords = line.getText().replaceAll("[^가-힣]", "");
                if (isFishing(line.getText()) && line.getElements().size() <= 1)
                    fish.add(line.getBoundingBox());
                // ML Kit sometimes merges "대결 낚시" into one element.
                if (isMergedFishingRow(lineWords))
                    mergedRows.add(line.getBoundingBox());
                for (Text.Element element : line.getElements()) {
                    String label = element.getText();
                    if (isFishing(label)) fish.add(element.getBoundingBox());
                    else if (isCompanion(label)) companions.add(element.getBoundingBox());
                }
            }
        }

        Target best = null;
        int w = image.getWidth(), h = image.getHeight();
        for (Rect r : fish) {
            if (!withinTouchableChat(r, w, h, composerTop)) {
                blockedTextCandidates++;
                continue;
            }
            boolean paired = false;
            for (Rect q : companions) {
                if (sameButtonRow(r, q, w, h)) {
                    paired = true;
                    break;
                }
            }
            // The OCR text next door may be missing even though both real
            // rounded gray buttons are plainly present. Require TWO
            // isolated filled rectangles, including the bright gutter.
            boolean visualPair = !paired && isTwoButtonRowByPixels(image, r);
            if (!(paired || visualPair) || !isGreyButton(image, r)) {
                blockedTextCandidates++;
                continue;
            }
            if (visualPair) visualPairFallbacks++;
            Target t = new Target(r.centerX(), r.centerY(),
                r.centerX() < w / 2 ? "왼쪽" : "오른쪽");
            if (best == null || t.y > best.y) best = t;
        }

        // If an entire row "강화 낚시" is recognized as one line, estimate
        // the final label bounds and verify the actual dual-button pixels.
        for (Rect row : mergedRows) {
            if (row == null) continue;
            String words = getMergedRowLabel(result, row);
            boolean fishAtEnd = words.endsWith("낚시");
            int labelWidth = Math.max(34, (int)(row.height() * 1.25));
            Rect label = fishAtEnd
                ? new Rect(row.right - labelWidth, row.top, row.right, row.bottom)
                : new Rect(row.left, row.top, row.left + labelWidth, row.bottom);
            if (!withinTouchableChat(label, w, h, composerTop)
                || !isGreyButton(image, label)
                || !isTwoButtonRowByPixels(image, label)) continue;
            mergedOcrPairs++;
            Target t = new Target(label.centerX(), label.centerY(),
                label.centerX() < w/2 ? "왼쪽" : "오른쪽");
            if (best == null || t.y > best.y) best = t;
        }
        return best;
    }

    private boolean isMergedFishingRow(String words) {
        return words.matches("(대결|강화|자랑하기)낚시|낚시(대결|강화|자랑하기)");
    }

    private String getMergedRowLabel(Text recognized, Rect bounds) {
        for (Text.TextBlock b : recognized.getTextBlocks()) {
            for (Text.Line line : b.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null && r.equals(bounds))
                    return line.getText().replaceAll("[^가-힣]", "");
            }
        }
        return "";
    }

    /**
     * Pixel-backed fallback for a two-button row:
     *  - fishing label is already recognized exactly;
     *  - both large rounded button bodies are light gray;
     *  - between them is a separate bright strip.
     * A lone gray chat bubble cannot pass the gutter requirement.
     */
    private boolean isTwoButtonRowByPixels(Bitmap image, Rect fish) {
        if (fish == null) return false;
        int w = image.getWidth(), h = image.getHeight();
        int x = fish.centerX(), y = fish.centerY();
        if (y < h * .17 || y > h * .85) return false;
        int shift = Math.round(w * .292f);
        int radiusX = Math.max(28, Math.round(w * .078f));
        int radiusY = Math.max(10, Math.round(h * .009f));
        for (int direction : new int[]{-1, 1}) {
            int otherX = x + direction * shift;
            if (otherX < w * .14 || otherX > w * .86) continue;
            int first = filledGreyCorners(image, x, y, radiusX, radiusY);
            int second = filledGreyCorners(image, otherX, y, radiusX, radiusY);
            if (first < 3 || second < 3) continue;

            // In the sample Kakao card, there is a ~6px WHITE gap between
            // the two 240-gray buttons. Search a narrow neighborhood to
            // tolerate label and OCR bounding-box offsets.
            int mid = (x + otherX) / 2;
            boolean hasBrightGutter = false;
            for (int dx=-10; dx<=10; dx+=2) {
                int gx=mid+dx;
                if (gx<1 || gx>=w-1) continue;
                int color=image.getPixel(gx,y);
                if (Color.red(color)>=252 && Color.green(color)>=252
                    && Color.blue(color)>=252) {
                    hasBrightGutter=true;
                    break;
                }
            }
            if (hasBrightGutter) return true;
        }
        return false;
    }

    private int filledGreyCorners(Bitmap image, int x, int y,
            int rx, int ry) {
        int count=0;
        for (int dx : new int[]{-rx, rx}) {
            for (int dy : new int[]{-ry, ry}) {
                if (grey(image, x+dx, y+dy)) count++;
            }
        }
        return count;
    }

    private boolean isFishing(String raw) {
        return "낚시".equals(raw.replaceAll("[^가-힣]", ""));
    }

    private Target findFishingFromNodes(Bitmap image, int composerTop) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
            || !KAKAO.contentEquals(root.getPackageName())) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        ArrayList<Rect> fish = new ArrayList<>();
        ArrayList<Rect> neighbor = new ArrayList<>();
        queue.add(root);
        int w = image.getWidth(), h = image.getHeight(), count = 0;
        while (!queue.isEmpty() && count++ < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser()) {
                String name = node.getText() == null ? ""
                    : node.getText().toString().trim();
                String desc = node.getContentDescription() == null ? ""
                    : node.getContentDescription().toString().trim();
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (withinTouchableChat(bounds, w, h, composerTop)) {
                    if ("낚시".equals(name) || "낚시".equals(desc))
                        fish.add(bounds);
                    if (isCompanion(name) || isCompanion(desc))
                        neighbor.add(bounds);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        Target best = null;
        for (Rect r : fish) {
            boolean paired = false;
            for (Rect q : neighbor) {
                if (sameButtonRow(r, q, w, h)) {
                    paired = true;
                    break;
                }
            }
            if (paired && isGreyButton(image, r)) {
                Target t = new Target(r.centerX(), r.centerY(),
                    r.centerX() < w / 2 ? "왼쪽" : "오른쪽");
                if (best == null || t.y > best.y) best = t;
            } else {
                blockedTextCandidates++;
            }
        }
        return best;
    }

    /**
     * Avoid an endless older-message scroll when the white catch-result
     * card itself extends beneath the fixed dark Kakao composer.
     */
    private boolean shouldRevealBottomCard(Text recognized, Bitmap image,
            int composerTop) {
        int w = image.getWidth(), h = image.getHeight();
        if (composerTop < h * .68 || composerTop > h * .96) return false;
        boolean fishResult = false;
        boolean catchSize = false;
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            String t = block.getText();
            if (t.contains("등급") || t.contains("자랑하기")
                || t.contains("놓아준다")) fishResult = true;
            if (t.contains("cm") || t.contains("㎝")) catchSize = true;
        }
        if (!fishResult || !catchSize || hasResultChoices(recognized))
            return false;

        int whiteSamples = 0;
        for (int i=0; i<3; i++) {
            int y = composerTop - 24 - i * Math.max(23, h / 45);
            for (double fraction : new double[]{.23,.44,.69}) {
                int x = (int)(w * fraction);
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int c = image.getPixel(x,y);
                if (Color.red(c) > 220 && Color.green(c) > 220
                    && Color.blue(c) > 220) whiteSamples++;
            }
        }
        return whiteSamples >= 4;
    }

    private void revealBottomCard(int width, int height, int composerTop) {
        if (!running || scanning == false || tapping || returningToLatest
            || !visibleKakaoChat()) return;
        lastRevealAt = SystemClock.uptimeMillis();
        status = "실제 낚시 버튼이 입력창에 가려짐 → 카드 위로 이동";
        // Finger UP = the message card moves UP, clearing the fixed bar.
        int x = (int)(width * .88);
        int fromY = Math.min((int)(height * .72), composerTop - 80);
        int toY = Math.max((int)(height * .36), fromY - (int)(height * .32));
        dispatchAction(x, fromY, x, toY, 280, Action.SCROLL, () -> {
            revealSwipes++;
            revealedBottomButtons++;
            recordEvent("가려진 하단 버튼 표시: " + revealSwipes);
            // Preserve revealSwipes across retries so we cannot enter a
            // rapid infinite scroll loop if the button is still hidden.
            lastVisualSample = null;
            status = "스크롤 완료 → 실제 낚시 버튼 재검사";
            schedule(500);
        });
    }

    private boolean isGreyButton(Bitmap image, Rect bounds) {
        int x = bounds.centerX(), y = bounds.centerY();
        int dx = bounds.width() > 95 ? Math.min(52, bounds.width() / 4)
            : Math.max(bounds.width(), Math.round(bounds.height() * 1.25f));
        int dy = bounds.height() > 48 ? Math.max(12, bounds.height() / 4)
            : Math.max(10, Math.round(bounds.height() * .83f));
        int matches = 0;
        if (grey(image, x - dx, y)) matches++;
        if (grey(image, x + dx, y)) matches++;
        if (grey(image, x, y - dy)) matches++;
        if (grey(image, x, y + dy)) matches++;
        // OCR text boxes can cover some of the gray button. Two clear
        // background samples plus the exact word are sufficient here.
        return matches >= 2;
    }

    private boolean grey(Bitmap image, int x, int y) {
        if (x < 1 || y < 1 || x >= image.getWidth() - 1
            || y >= image.getHeight() - 1) return false;
        int c = image.getPixel(x, y);
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return r >= 211 && r <= 250 && Math.abs(r - g) <= 13 && Math.abs(r - b) <= 13;
    }

    private enum Action { FISH, CLOSE, SCROLL }

    private void tap(int x, int y) {
        if (!running || !visibleKakaoChat()) return;
        dispatchAction(x, y, x, y, 80, Action.FISH, null);
    }

    /**
     * Every tap and swipe goes through one serial gesture dispatcher.
     * No second gesture starts until Android calls onCompleted/onCancelled.
     */
    private boolean dispatchAction(int fromX, int fromY, int toX, int toY,
            long durationMs, Action action, Runnable success) {
        if (!running || tapping || !onKakao()) return false;
        if (action != Action.CLOSE && !visibleKakaoChat()) return false;
        if (action == Action.FISH) {
            long remaining = nextFishingTapEligibleAt - SystemClock.uptimeMillis();
            if (remaining > 0) {
                status = "낚시 터치 속도 제한 → " + remaining + "ms 대기";
                return false;
            }
        }
        final int id = ++gestureToken;
        recordProgress();
        tapping = true;
        main.removeCallbacks(ticker);
        Path path = new Path();
        path.moveTo(fromX, fromY);
        if (fromX != toX || fromY != toY) path.lineTo(toX, toY);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
            .build();
        try {
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) {
                    if (id != gestureToken) return;
                    tapping = false;
                    main.removeCallbacks(tapTimeout);
                    recordProgress();
                    if (!running) return;
                    if (action == Action.FISH) {
                        clicks++;
                        lastFishingTapAt = SystemClock.uptimeMillis();
                        lastVisualChangeAt = lastFishingTapAt;
                        status = "낚시 클릭 완료";
                        recordEvent("낚시 버튼 터치 완료");
                        if (olderSwipes > 0) {
                            // New bot messages may appear while the chat is
                            // scrolled upward. Navigate back to newest.
                            int steps = olderSwipes;
                            int session = sessionId;
                            main.postDelayed(() -> {
                                if (running && session == sessionId)
                                    returnToLatest(steps);
                            }, 600);
                        } else {
                            // Not 1.8 seconds from this callback: the timing
                            // guard is anchored at the actual dispatch time.
                            schedule(250);
                        }
                    } else if (action == Action.CLOSE) {
                        popupsClosed++;
                        olderSwipes = 0;
                        noButtonSince = 0;
                        lastVisualSample = null;
                        status = "웹페이지 X 닫기 완료 → 채팅 재확인";
                        schedule(850);
                    } else if (success != null) {
                        success.run();
                    } else {
                        schedule(850);
                    }
                }

                @Override public void onCancelled(GestureDescription value) {
                    if (id != gestureToken) return;
                    tapping = false;
                    cancelledGestures++;
                    main.removeCallbacks(tapTimeout);
                    returningToLatest = false;
                    olderSwipes = 0;
                    recordProgress();
                    recordEvent("제스처 취소");
                    if (running) {
                        status = "제스처 취소됨 → 잠시 후 재탐색";
                        schedule(1150);
                    }
                }
            }, main);
            if (accepted) {
                if (action == Action.FISH) {
                    long now = SystemClock.uptimeMillis();
                    lastFishingDispatchAt = now;
                    lastChosenTapIntervalMs = MIN_FISH_TAP_INTERVAL_MS
                        + random.nextInt(MAX_FISH_TAP_INTERVAL_MS - MIN_FISH_TAP_INTERVAL_MS + 1);
                    nextFishingTapEligibleAt = now + lastChosenTapIntervalMs;
                    recordEvent("터치 간격 예약: " + lastChosenTapIntervalMs + "ms");
                }
                main.removeCallbacks(tapTimeout);
                main.postDelayed(tapTimeout, GESTURE_TIMEOUT_MS);
            } else {
                tapping = false;
                returningToLatest = false;
                cancelledGestures++;
                status = "제스처 전송 실패";
                recordEvent("제스처 전송 실패");
                recordProgress();
                schedule(1050);
            }
        } catch (Exception error) {
            tapping = false;
            returningToLatest = false;
            cancelledGestures++;
            status = "제스처 예외 → 재시도";
            recordEvent("제스처 예외");
            recordProgress();
            schedule(1100);
        }
        // The caller receives a false result only if the operation could
        // not even be attempted; immediate dispatch failure already reschedules.
        return true;
    }

    private static final class Target {
        final int x, y;
        final String side;
        Target(int x, int y, String side) {
            this.x = x;
            this.y = y;
            this.side = side;
        }
    }
}
