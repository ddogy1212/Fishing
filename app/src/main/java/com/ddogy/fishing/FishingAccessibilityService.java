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
    private static final int MIN_FISH_TAP_INTERVAL_MS = 2000;
    private static final int MAX_FISH_TAP_INTERVAL_MS = 2200;
    private static final int MAX_OLDER_SWIPES = 3;
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

    /**
     * Detect older installs of THIS project that may run a second
     * AccessibilityService and cancel our gestures. Does not disable or
     * manipulate Android accessibility settings.
     */
    public String getConflictingService() {
        String enabled = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
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
        if (!onKakao()) {
            status = "카카오톡 화면 대기 중";
            schedule(950);
            return;
        }

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
            if (!inChat) {
                // Do not tap or swipe an unknown window. This specific site
                // is opened by the fishing bot; returning to chat is expected.
                if (isFishingWebsite(text)) {
                    status = "낚시 웹페이지 감지 → 채팅 복귀";
                    closeFishingWebsite(text, image);
                } else {
                    status = "카카오톡 낚시 채팅방 복귀 대기";
                }
                return;
            }

            recordVisualFrame(image);
            Target target = findFishing(text, image);
            if (target == null) target = findFishingFromNodes(image);
            long now = SystemClock.uptimeMillis();

            if (target != null) {
                // A visible, verified fishing button ALWAYS wins over refresh
                // or scrolling logic. The old 1.4 branch was scrolling
                // even with a visible button; that caused an endless loop.
                noButtonSince = 0;
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

    private boolean isFishingWebsite(Text recognized) {
        for (Text.TextBlock b : recognized.getTextBlocks()) {
            String s = b.getText().toLowerCase(java.util.Locale.ROOT);
            if (s.contains("couchfisher.com")
                || s.contains("카카오톡으로 돌아가면")
                || s.contains("낚시를 계속할 수 있어요")) return true;
        }
        return false;
    }

    /** Only operate on a positively recognized fishing website inside Kakao. */
    private void closeFishingWebsite(Text text, Bitmap image) {
        if (!running || !onKakao() || visibleKakaoChat()) return;
        Target close = findWebCloseFromNodes(image.getWidth(), image.getHeight());
        if (close == null) close = findWebCloseFromOcr(text, image);
        if (close == null) close = findWebClosePixels(image);
        if (close != null) {
            final int x = close.x, y = close.y;
            dispatchAction(x, y, x, y, 80, Action.CLOSE, null);
        } else {
            // Back is only used for a recognized in-app fishing website.
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                popupsClosed++;
                olderSwipes = 0;
                noButtonSince = 0;
                lastVisualSample = null;
                status = "낚시 웹페이지 뒤로가기 완료";
            } else {
                status = "웹페이지 닫기 실패 → 다시 확인";
            }
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

    private Target findFishing(Text result, Bitmap image) {
        Target best = null;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (isFishing(line.getText())) {
                    best = choose(image, line.getBoundingBox(), best);
                }
                for (Text.Element word : line.getElements()) {
                    if (isFishing(word.getText())) {
                        best = choose(image, word.getBoundingBox(), best);
                    }
                }
            }
        }
        return best;
    }

    private boolean isFishing(String raw) {
        return "낚시".equals(raw.replaceAll("[^가-힣]", ""));
    }

    private Target choose(Bitmap image, Rect rect, Target best) {
        if (rect == null || rect.width() <= 6 || rect.height() <= 6) return best;
        int w = image.getWidth(), h = image.getHeight();
        int x = rect.centerX(), y = rect.centerY();
        if (x <= w * .10 || x >= w * .91 || y <= h * .14 || y >= h * .91) return best;
        if (!isGreyButton(image, rect)) return best;
        Target target = new Target(x, y, x < w / 2 ? "왼쪽" : "오른쪽");
        return best == null || best.y < target.y ? target : best;
    }

    private Target findFishingFromNodes(Bitmap image) {
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
                String name = node.getText() == null ? "" : node.getText().toString().trim();
                String description = node.getContentDescription() == null ? ""
                    : node.getContentDescription().toString().trim();
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 10 && bounds.width() < w * .52
                    && bounds.height() > 10 && bounds.height() < h * .11
                    && bounds.centerY() > h * .14 && bounds.centerY() < h * .91) {
                    if ("낚시".equals(name) || "낚시".equals(description)) fish.add(bounds);
                    if ("대결".equals(name) || "강화".equals(name)
                        || "대결".equals(description) || "강화".equals(description)) {
                        neighbor.add(bounds);
                    }
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
                if (Math.abs(q.centerY() - r.centerY()) < Math.max(24, h * .025)
                    && Math.abs(q.centerX() - r.centerX()) > 45) {
                    paired = true;
                    break;
                }
            }
            // The companion button is sometimes clipped off-screen while
            // scrolling older chat messages. Exact "낚시" text + genuine
            // light gray button backing is enough in that case.
            if (isGreyButton(image, r)) {
                Target t = new Target(r.centerX(), r.centerY(),
                    r.centerX() < w / 2 ? "왼쪽" : "오른쪽");
                if (best == null || t.y > best.y) best = t;
            }
        }
        return best;
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
