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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable ticker = this::scanOnce;

    private TextRecognizer recognizer;
    private boolean running = false;
    private boolean working = false;
    private boolean seenChat = false;
    private long clicks = 0;
    private long lastTapAt = 0;
    private String status = "준비 완료";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        status = "준비 완료";
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Never automatically start or acquire additional app permissions.
    }

    @Override public void onInterrupt() {
        stopRunning();
    }

    @Override public void onDestroy() {
        stopRunning();
        if (recognizer != null) recognizer.close();
        if (active == this) active = null;
        super.onDestroy();
    }

    public void startRunning() {
        if (running) return;
        running = true;
        working = false;
        seenChat = false;
        status = "카카오톡 낚시 채팅 화면으로 이동하세요";
        schedule(300);
    }

    public void stopRunning() {
        running = false;
        working = false;
        main.removeCallbacks(ticker);
        status = "중지했습니다";
    }

    public boolean isRunning() { return running; }
    public long getClickCount() { return clicks; }
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
        boolean chatComposer = containsChatComposer(root);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult shot) {
                        if (!running) { working = false; return; }
                        Bitmap copy = null;
                        HardwareBuffer buffer = shot.getHardwareBuffer();
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, shot.getColorSpace());
                            if (hardware != null) {
                                copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                hardware.recycle();
                            }
                        } catch (Exception e) {
                            status = "화면 가져오기 실패: " + e.getClass().getSimpleName();
                        } finally {
                            buffer.close();
                        }
                        if (copy == null) {
                            working = false;
                            schedule(1100);
                            return;
                        }
                        analyze(copy, chatComposer);
                    }

                    @Override public void onFailure(int errorCode) {
                        working = false;
                        status = "화면 캡처 실패 (" + errorCode + ") — 재시도";
                        schedule(1400);
                    }
                });
        } catch (Exception e) {
            working = false;
            status = "스크린샷 기능을 사용할 수 없어요";
            schedule(1500);
        }
    }

    private void analyze(Bitmap frame, boolean chatComposer) {
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(frame, 0));
        } catch (Exception e) {
            frame.recycle();
            working = false;
            status = "문자 인식 시작 실패";
            schedule(1300);
            return;
        }

        task.addOnSuccessListener(result -> {
            if (!running) return;
            Target fishing = findFishing(result, frame);
            if (fishing != null) {
                seenChat = true;
                status = "낚시 버튼 인식 (" + fishing.side + ") — 터치";
                tap(fishing.x, fishing.y, true);
                return;
            }

            // A popup may hide the composer and the fishing controls.
            // Never tap an arbitrary top-left point: only a confirmed close target.
            if (seenChat && !chatComposer) {
                Target close = findCloseFromAccessibility(frame.getWidth(), frame.getHeight());
                if (close == null) close = findCloseFromOcr(result, frame);
                if (close != null) {
                    status = "팝업 닫기(X) → 채팅 복귀";
                    tap(close.x, close.y, false);
                    return;
                }
                status = "팝업 의심: 닫기(X) 확인 불가, 안전하게 대기";
            } else {
                status = "회색 '낚시' 버튼을 찾는 중 (미인식 시 클릭 안 함)";
            }
        }).addOnFailureListener(e -> {
            if (running) status = "한글 문자 인식 실패 — 재시도";
        }).addOnCompleteListener(t -> {
            frame.recycle();
            working = false;
            if (running) nextCycle();
        });
    }

    /** The OCR *element* must exactly be 낚시, not a larger message like 낚시터. */
    private Target findFishing(Text text, Bitmap frame) {
        Target best = null;
        int w = frame.getWidth(), h = frame.getHeight();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String cleaned = element.getText().replaceAll("[^가-힣]", "");
                    if (!"낚시".equals(cleaned)) continue;
                    Rect r = element.getBoundingBox();
                    if (r == null || r.width() <= 6 || r.height() <= 6) continue;

                    int cx = r.centerX(), cy = r.centerY();
                    // Exclude heading texts, chat mentions and the keyboard area.
                    if (cy < h * 0.20 || cy > h * 0.91) continue;
                    if (cx < w * 0.11 || cx > w * 0.90) continue;
                    if (!looksLikeGreyButton(frame, r)) continue;

                    // Select the latest (lowest) visible button, not old chat cards.
                    if (best == null || cy > best.y) {
                        best = new Target(cx, cy, cx < w / 2 ? "왼쪽" : "오른쪽");
                    }
                }
            }
        }
        return best;
    }

    /** Check an actual grey rounded-button background around the OCR letters. */
    private boolean looksLikeGreyButton(Bitmap frame, Rect letters) {
        int cx = letters.centerX(), cy = letters.centerY();
        int gapX = Math.max(letters.width(), Math.round(letters.height() * 1.25f));
        int gapY = Math.max(10, Math.round(letters.height() * 0.83f));
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
