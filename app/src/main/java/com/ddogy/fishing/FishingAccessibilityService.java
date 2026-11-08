package com.ddogy.fishing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
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
 * Minimal, on-device and user-controlled fishing button assistant.
 * Only scans visible KakaoTalk chats and taps verified gray "낚시" buttons.
 * No popup closing, swiping or background navigation.
 */
public class FishingAccessibilityService extends AccessibilityService {
    public static volatile FishingAccessibilityService active;

    private static final String KAKAO = "com.kakao.talk";
    private static final String PREFS = "fishing_settings";
    private static final String USER_STARTED = "user_requested_running";
    private static final int OCR_TIMEOUT_MS = 9000;
    private static final int GESTURE_TIMEOUT_MS = 4000;
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
    private String status = "준비 완료";

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
        status = "터치 응답 지연 → 다시 검사";
        schedule(1200);
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        recognizer = TextRecognition.getClient(
            new KoreanTextRecognizerOptions.Builder().build());
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(USER_STARTED, false)) {
            running = true;
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
        if (running && !scanning && !tapping) schedule(900);
    }

    @Override public void onDestroy() {
        stopRunning();
        if (recognizer != null) recognizer.close();
        if (active == this) active = null;
        super.onDestroy();
    }

    public void startRunning() {
        if (running) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(USER_STARTED, true).apply();
        running = true;
        scanning = false;
        tapping = false;
        scanToken++;
        gestureToken++;
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
        main.removeCallbacks(ticker);
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(tapTimeout);
        status = "중지됨";
    }

    public boolean isRunning() { return running; }
    public long getClickCount() { return clicks; }
    public long getScans() { return scans; }
    public long getTimeouts() { return failures; }
    public long getCaptureFailures() { return failures; }
    public long getUnmatchedScans() { return missed; }
    public long getRecoveryCount() { return 0; }
    public long getCancelledGestures() { return cancelledGestures; }
    public String getCurrentStatus() { return status; }

    private void schedule(long delayMs) {
        main.removeCallbacks(ticker);
        if (running && !scanning && !tapping) main.postDelayed(ticker, delayMs);
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
        if (!running || scanning || tapping) return;
        if (!visibleKakaoChat()) {
            status = "카카오톡 채팅 화면 대기 중";
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
            Target target = findFishing(text, image);
            if (target == null) target = findFishingFromNodes(image);
            if (target != null && visibleKakaoChat()) {
                status = "낚시 버튼 인식 (" + target.side + ") → 클릭";
                tap(target.x, target.y);
            } else {
                missed++;
                status = "'낚시' 버튼 탐색 중";
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
        schedule(delayMs);
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
        if (x <= w * .10 || x >= w * .91 || y <= h * .20 || y >= h * .91) return best;
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
                    && bounds.centerY() > h * .2 && bounds.centerY() < h * .91) {
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
            if (paired && isGreyButton(image, r)) {
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
        return matches >= 3;
    }

    private boolean grey(Bitmap image, int x, int y) {
        if (x < 1 || y < 1 || x >= image.getWidth() - 1
            || y >= image.getHeight() - 1) return false;
        int c = image.getPixel(x, y);
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return r >= 211 && r <= 250 && Math.abs(r - g) <= 13 && Math.abs(r - b) <= 13;
    }

    private void tap(int x, int y) {
        if (!running || tapping || !visibleKakaoChat()) return;
        final int id = ++gestureToken;
        tapping = true;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
            .build();
        try {
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) {
                    if (id != gestureToken) return;
                    tapping = false;
                    main.removeCallbacks(tapTimeout);
                    if (running) {
                        clicks++;
                        status = "낚시 터치 완료 · 다음 버튼 기다리는 중";
                        schedule(1800 + random.nextInt(401));
                    }
                }

                @Override public void onCancelled(GestureDescription value) {
                    if (id != gestureToken) return;
                    tapping = false;
                    cancelledGestures++;
                    main.removeCallbacks(tapTimeout);
                    if (running) {
                        status = "터치 취소됨 → 다시 검사";
                        schedule(1100);
                    }
                }
            }, main);
            if (accepted) {
                main.removeCallbacks(tapTimeout);
                main.postDelayed(tapTimeout, GESTURE_TIMEOUT_MS);
            } else {
                tapping = false;
                cancelledGestures++;
                schedule(950);
            }
        } catch (Exception error) {
            tapping = false;
            cancelledGestures++;
            status = "터치 실패 → 다시 검사";
            schedule(1050);
        }
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
