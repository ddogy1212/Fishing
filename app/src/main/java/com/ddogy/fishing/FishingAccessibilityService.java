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
import java.util.Random;

/**
 * User-controlled, on-device fishing-button assistant.
 * Intentionally refuses to guess coordinates when OCR/popup evidence is weak.
 */
public class FishingAccessibilityService extends AccessibilityService {
    public static volatile FishingAccessibilityService active;

    private static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final int MIN_INTERVAL_MS = 1800;
    private static final int MAX_INTERVAL_MS = 2200;
    private static final String PREFS = "fishing_settings";
    private static final String WANTED = "user_requested_running";
    private static final long SCAN_TIMEOUT_MS = 9000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable ticker = this::scanOnce;
    private final Runnable watchdog = () -> {
        if (!running || !working) return;
        // OCR and screenshot callbacks do not have a guaranteed deadline.
        // Discard stale callback work, then schedule a fresh scan.
        scanToken++;
        working = false;
        scanTimeouts++;
        status = "화면 인식 지연 복구 중 (" + scanTimeouts + "회)";
        schedule(650);
    };

    private TextRecognizer recognizer;
    private boolean running = false;
    private boolean working = false;
    private boolean seenChat = false;
    private long clicks = 0;
    private long lastTapAt = 0;
    private int scanToken = 0;
    private long scans = 0;
    private long scanTimeouts = 0;
    private long captureFailures = 0;
    private long unmatchedScans = 0;
    private String status = "준비 완료";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        status = "준비 완료";
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WANTED, false)) {
            // The process may be reclaimed without onDestroy. Resume only if
            // the user had explicitly started a session earlier.
            running = true;
            status = "이전 자동 낚시 세션 복원 중";
            schedule(500);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Never automatically start or acquire additional app permissions.
    }

    @Override public void onInterrupt() {
        // A temporary accessibility interruption is NOT an explicit user stop.
        // The next scan will re-check the foreground app before any tap.
        if (running) {
            status = "접근성 일시 중단 — 자동 복구 확인 중";
            if (!working) schedule(800);
        }
    }

    @Override public void onDestroy() {
        running = false;
        working = false;
        scanToken++;
        main.removeCallbacks(ticker);
        main.removeCallbacks(watchdog);
        // A deliberate accessibility-service shutdown must not secretly
        // restart automation when the permission is re-enabled later.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WANTED, false).apply();
        if (recognizer != null) recognizer.close();
        if (active == this) active = null;
        super.onDestroy();
    }

    public void startRunning() {
        if (running) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WANTED, true).apply();
        running = true;
        working = false;
        seenChat = false;
        scanToken++;
        lastTapAt = 0;
        status = "카카오톡 낚시 채팅 화면으로 이동하세요";
        schedule(300);
    }

    public void stopRunning() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WANTED, false).apply();
        running = false;
        working = false;
        scanToken++;
        main.removeCallbacks(ticker);
        main.removeCallbacks(watchdog);
        status = "사용자가 중지했습니다";
    }

    public boolean isRunning() { return running; }
    public long getClickCount() { return clicks; }
    public long getScans() { return scans; }
    public long getTimeouts() { return scanTimeouts; }
    public long getCaptureFailures() { return captureFailures; }
    public long getUnmatchedScans() { return unmatchedScans; }
    public String getCurrentStatus() { return status; }

    private void schedule(long ms) {
        main.removeCallbacks(ticker);
        if (running) main.postDelayed(ticker, ms);
    }

    private long nextDelay() {
        // Respect the requested 1.8–2.2 second *tap* interval when possible.
        return MIN_INTERVAL_MS + random.nextInt(MAX_INTERVAL_MS - MIN_INTERVAL_MS + 1);
    }

    private void nextCycle() {
        long nextTarget = lastTapAt == 0 ? 0 : lastTapAt + nextDelay();
        long wait = Math.max(250, nextTarget - SystemClock.uptimeMillis());
        schedule(wait);
    }

    private void scanOnce() {
        if (!running || working) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !KAKAO_PACKAGE.contentEquals(root.getPackageName())) {
            status = "카카오톡 화면을 기다리고 있어요";
            schedule(900);
            return;
        }

        working = true;
        scans++;
        int token = ++scanToken;
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, SCAN_TIMEOUT_MS);
        boolean chatComposer = containsChatComposer(root);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult shot) {
                        HardwareBuffer buffer = shot.getHardwareBuffer();
                        if (!running || token != scanToken) {
                            if (buffer != null) buffer.close();
                            return;
                        }
                        Bitmap copy = null;
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, shot.getColorSpace());
                            if (hardware != null) {
                                copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                hardware.recycle();
                            }
                        } catch (Exception e) {
                            status = "화면 가져오기 실패: " + e.getClass().getSimpleName();
                        } finally {
                            if (buffer != null) buffer.close();
                        }
                        if (copy == null) {
                            captureFailures++;
                            finishScan(token, 1100);
                            return;
                        }
                        analyze(copy, chatComposer, token);
                    }

                    @Override public void onFailure(int errorCode) {
                        if (!running || token != scanToken) return;
                        captureFailures++;
                        status = "화면 캡처 실패 (" + errorCode + ") — 재시도";
                        finishScan(token, 1150);
                    }
                });
        } catch (Exception e) {
            captureFailures++;
            status = "스크린샷 예외 — 재시도";
            finishScan(token, 1200);
        }
    }

    private void finishScan(int token, long retryWait) {
        if (token != scanToken) return;
        working = false;
        main.removeCallbacks(watchdog);
        if (running) {
            if (retryWait > 0) schedule(retryWait);
            else nextCycle();
        }
    }

    private void analyze(Bitmap frame, boolean chatComposer, int token) {
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(frame, 0));
        } catch (Exception e) {
            frame.recycle();
            status = "문자 인식 시작 실패 — 재시도";
            finishScan(token, 1150);
            return;
        }

        task.addOnSuccessListener(result -> {
            if (!running || token != scanToken) return;
            Target fishing = findFishing(result, frame);
            // Accessibility nodes are a backup when OCR misses the '낚시' text.
            if (fishing == null) fishing = findFishingFromAccessibility(frame);

            // Check the popup close button before potentially tapping a
            // still-visible old fishing card underneath a popup.
            if (seenChat && !chatComposer) {
                Target close = findCloseFromAccessibility(frame.getWidth(), frame.getHeight());
                if (close == null) close = findCloseFromOcr(result, frame);
                if (close == null) close = findCloseFromPixels(frame);
                if (close != null) {
                    status = "팝업 닫기(X) → 채팅 복귀";
                    tap(close.x, close.y, false);
                    return;
                }
            }

            if (fishing != null) {
                seenChat = true;
                status = "낚시 버튼 인식 (" + fishing.side + ") — 터치";
                tap(fishing.x, fishing.y, true);
                return;
            }

            unmatchedScans++;
            status = seenChat && !chatComposer
                ? "팝업 의심: X 확인 불가, 재탐색 (" + unmatchedScans + ")"
                : "'낚시' 버튼 다시 탐색 중 (" + unmatchedScans + "회)";
        }).addOnFailureListener(e -> {
            if (running && token == scanToken)
                status = "한글 인식 실패 — 안전하게 재시도";
        }).addOnCompleteListener(t -> {
            if (!frame.isRecycled()) frame.recycle();
            finishScan(token, 0);
        });
    }

    /** The OCR *element* must exactly be 낚시, not a larger message like 낚시터. */
    private Target findFishing(Text text, Bitmap frame) {
        Target best = null;
        int w = frame.getWidth(), h = frame.getHeight();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                // ML Kit sometimes combines/splits the characters across
                // elements. Checking the full line catches these cases.
                if ("낚시".equals(line.getText().replaceAll("[^가-힣]", ""))) {
                    best = chooseFishingRect(frame, line.getBoundingBox(), best);
                }
                for (Text.Element element : line.getElements()) {
                    if ("낚시".equals(element.getText().replaceAll("[^가-힣]", ""))) {
                        best = chooseFishingRect(frame, element.getBoundingBox(), best);
                    }
                }
            }
        }
        return best;
    }

    private Target chooseFishingRect(Bitmap frame, Rect r, Target best) {
        if (r == null || r.width() <= 6 || r.height() <= 6) return best;
        int w = frame.getWidth(), h = frame.getHeight();
        int cx = r.centerX(), cy = r.centerY();
        if (cy < h * 0.20 || cy > h * 0.91) return best;
        if (cx < w * 0.11 || cx > w * 0.90) return best;
        if (!looksLikeGreyButton(frame, r)) return best;
        if (best == null || cy > best.y)
            return new Target(cx, cy, cx < w / 2 ? "왼쪽" : "오른쪽");
        return best;
    }

    /**
     * Fallback for WebView/Chat UI buttons exposed through accessibility,
     * even if on-device OCR misses their characters. Requires a same-row
     * companion labeled '대결' or '강화'; no arbitrary coordinate guessing.
     */
    private Target findFishingFromAccessibility(Bitmap frame) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
            || !KAKAO_PACKAGE.contentEquals(root.getPackageName())) return null;

        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        java.util.ArrayList<Rect> fishing = new java.util.ArrayList<>();
        java.util.ArrayList<Rect> peers = new java.util.ArrayList<>();
        todo.add(root);
        int visited = 0, w = frame.getWidth(), h = frame.getHeight();
        while (!todo.isEmpty() && visited++ < 1000) {
            AccessibilityNodeInfo node = todo.removeFirst();
            if (node.isVisibleToUser()) {
                String value = node.getText() == null ? "" : node.getText().toString().trim();
                String desc = node.getContentDescription() == null ? ""
                    : node.getContentDescription().toString().trim();
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 10 && bounds.width() < w * .52
                    && bounds.height() > 10 && bounds.height() < h * .11
                    && bounds.centerX() > w * .10 && bounds.centerX() < w * .91
                    && bounds.centerY() > h * .20 && bounds.centerY() < h * .91) {
                    if ("낚시".equals(value) || "낚시".equals(desc))
                        fishing.add(bounds);
                    if ("대결".equals(value) || "강화".equals(value)
                        || "대결".equals(desc) || "강화".equals(desc))
                        peers.add(bounds);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) todo.addLast(child);
            }
        }

        Target best = null;
        for (Rect r : fishing) {
            boolean paired = false;
            for (Rect peer : peers) {
                int dy = Math.abs(peer.centerY() - r.centerY());
                int dx = Math.abs(peer.centerX() - r.centerX());
                if (dy <= Math.max(24, h * .02) && dx > 45 && dx < w * .55) {
                    paired = true;
                    break;
                }
            }
            if (paired && looksLikeGreyButton(frame, r)) {
                Target t = new Target(r.centerX(), r.centerY(),
                    r.centerX() < w / 2 ? "왼쪽 (접근성)" : "오른쪽 (접근성)");
                if (best == null || t.y > best.y) best = t;
            }
        }
        return best;
    }

    /** Check an actual grey rounded-button background around the OCR letters. */
    private boolean looksLikeGreyButton(Bitmap frame, Rect letters) {
        int cx = letters.centerX(), cy = letters.centerY();
        // OCR gives narrow text bounds, while accessibility sometimes gives
        // the entire wide button. Sample *inside* either kind of bounds.
        int gapX = letters.width() > 95
            ? Math.min(52, letters.width() / 4)
            : Math.max(letters.width(), Math.round(letters.height() * 1.25f));
        int gapY = letters.height() > 48
            ? Math.max(12, letters.height() / 4)
            : Math.max(10, Math.round(letters.height() * 0.83f));
        int matched = 0;

        if (grey(frame, cx - gapX, cy)) matched++;
        if (grey(frame, cx + gapX, cy)) matched++;
        if (grey(frame, cx, cy - gapY)) matched++;
        if (grey(frame, cx, cy + gapY)) matched++;
        return matched >= 3;
    }

    private boolean grey(Bitmap bmp, int x, int y) {
        if (x < 1 || y < 1 || x >= bmp.getWidth()-1 || y >= bmp.getHeight()-1) return false;
        int c = bmp.getPixel(x, y);
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return r >= 211 && r <= 250 && Math.abs(r-g) <= 13 && Math.abs(r-b) <= 13;
    }

    private boolean containsChatComposer(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int n = 0;
        while (!queue.isEmpty() && n++ < 350) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (node.isEditable()
                || (text != null && text.toString().contains("메시지 입력"))
                || (desc != null && desc.toString().contains("메시지 입력"))) {
                return true;
            }
            for (int j = 0; j < node.getChildCount(); j++) {
                AccessibilityNodeInfo child = node.getChild(j);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    /** Prefer an actual accessible close control in the popup's top left. */
    private Target findCloseFromAccessibility(int w, int h) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !KAKAO_PACKAGE.contentEquals(root.getPackageName())) return null;

        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        int visited = 0;
        while (!nodes.isEmpty() && visited++ < 400) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String value = ((t == null ? "" : t.toString()) + " " +
                    (d == null ? "" : d.toString())).trim().toLowerCase();
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            boolean topLeft = r.centerX() > 5 && r.centerX() < w * 0.29
                    && r.centerY() > h * 0.025 && r.centerY() < h * 0.30
                    && r.width() < w * 0.35 && r.height() < h * 0.13;
            if (topLeft && node.isVisibleToUser()
                    && (value.equals("x") || value.equals("×") || value.equals("✕")
                    || value.equals("닫기") || value.contains("닫기 버튼")
                    || value.equals("close") || value.equals("close button"))) {
                return new Target(r.centerX(), r.centerY(), "닫기");
            }
            for (int j = 0; j < node.getChildCount(); j++) {
                AccessibilityNodeInfo child = node.getChild(j);
                if (child != null) nodes.addLast(child);
            }
        }
        return null;
    }

    /** OCR fallback: only a standalone X near the top-left of a confirmed popup. */
    private Target findCloseFromOcr(Text text, Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String word = element.getText().trim();
                    Rect r = element.getBoundingBox();
                    if (r == null) continue;
                    if (r.centerX() > 8 && r.centerX() < w * .29
                        && r.centerY() > h * .025 && r.centerY() < h * .30
                        && r.width() < w * .11 && r.height() < h * .10
                        && (word.equals("X") || word.equals("×") || word.equals("✕")
                            || word.equals("닫기"))) {
                        return new Target(r.centerX(), r.centerY(), "OCR 닫기");
                    }
                }
            }
        }
        return null;
    }

    /**
     * Last resort for a graphic-only X icon which accessibility/OCR cannot read.
     * Detect a high-contrast TWO-diagonal cross; a back arrow does not qualify.
     * Restricted to the top-left corner and only used after a chat was seen.
     */
    private Target findCloseFromPixels(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int bestScore = 0, bestX = -1, bestY = -1;
        int fromX = Math.max(15, Math.round(w * .015f));
        int toX = Math.min(Math.round(w * .24f), 180);
        int fromY = Math.round(h * .045f);
        int toY = Math.round(h * .235f);

        for (int y = fromY; y < toY; y += 4) {
            for (int x = fromX; x < toX; x += 4) {
                int matched = 0, sumScore = 0, sign = 0;
                for (int radius : new int[]{5, 8, 11}) {
                    if (x - radius < 0 || x + radius >= w ||
                            y - radius < 0 || y + radius >= h) continue;

                    int diag = (light(bmp, x-radius, y-radius)
                        + light(bmp, x+radius, y+radius)
                        + light(bmp, x-radius, y+radius)
                        + light(bmp, x+radius, y-radius)) / 4;
                    int cardinal = (light(bmp, x-radius, y)
                        + light(bmp, x+radius, y)
                        + light(bmp, x, y-radius)
                        + light(bmp, x, y+radius)) / 4;
                    int delta = diag - cardinal;
                    if (Math.abs(delta) >= 52 && (sign == 0 || (delta > 0 ? 1 : -1) == sign)) {
                        matched++;
                        sumScore += Math.abs(delta);
                        sign = delta > 0 ? 1 : -1;
                    }
                }
                if (matched >= 3 && sumScore > bestScore) {
                    bestScore = sumScore;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return bestScore >= 190 ? new Target(bestX, bestY, "그림 X") : null;
    }

    private int light(Bitmap bmp, int x, int y) {
        int c = bmp.getPixel(x, y);
        return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
    }

    private void tap(int x, int y, boolean fishing) {
        if (!running) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !KAKAO_PACKAGE.contentEquals(root.getPackageName())) return;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture,
            new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gd) {
                    if (running) status = fishing ? "낚시 완료, 다음 인식 대기" : "닫기 완료, 채팅 복귀 확인 중";
                }
                @Override public void onCancelled(GestureDescription gd) {
                    if (running) status = "터치 취소됨";
                }
            }, main);
        if (dispatched) {
            lastTapAt = SystemClock.uptimeMillis();
            if (fishing) clicks++;
        } else {
            status = "터치 요청 실패";
        }
    }

    private static final class Target {
        final int x, y;
        final String side;
        Target(int x, int y, String side) {
            this.x = x; this.y = y; this.side = side;
        }
    }
}
