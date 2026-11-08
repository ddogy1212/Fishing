package com.ddogy.fishing;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView details;
    private Button start;
    private Button stop;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 18, 25));
        getWindow().setNavigationBarColor(Color.rgb(15, 18, 25));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 18, 25));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(42), dp(24), dp(24));
        scroll.addView(layout);

        TextView title = text("🎣 Fishing", 31, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title);
        TextView subtitle = text("카카오톡 낚시 버튼 자동 인식", 16, Color.rgb(190, 198, 214));
        subtitle.setPadding(0, dp(10), 0, dp(26));
        layout.addView(subtitle);

        status = text("접근성 서비스 확인 중", 19, Color.WHITE);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(0, 0, 0, dp(9));
        layout.addView(status);
        details = text("", 15, Color.rgb(190, 198, 214));
        details.setPadding(0, 0, 0, dp(25));
        layout.addView(details);

        TextView disclosure = text(
            "권한 안내: 시작을 허용하면 Fishing은 실행 중인 카카오톡 화면을 읽어" +
            " '낚시' 버튼을 찾아 터치하고, 버튼이 가려지면 채팅을 스크롤합니다." +
            " 낚시 웹페이지가 열리거나 카카오톡에서 사진 뷰어로 이동하면" +
            " 이전 화면으로 돌아가기 위해 닫기/뒤로가기를 수행할 수 있습니다." +
            " 채팅에 표시된 내용이 화면 분석에 포함될 수 있습니다." +
            " 이 앱은 화면 데이터를 저장하거나 인터넷으로 전송하지 않습니다." +
            " 언제든 중지하거나 접근성 권한을 해제할 수 있습니다.",
            14, Color.rgb(225, 207, 178));
        disclosure.setPadding(0, 0, 0, dp(16));
        disclosure.setLineSpacing(dp(3), 1f);
        layout.addView(disclosure);

        Button settings = button("1. 접근성 설정 열기", Color.rgb(65, 77, 100));
        settings.setOnClickListener(v -> showDisclosure(
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        layout.addView(settings);

        start = button("2. 자동 낚시 시작 ▶", Color.rgb(217, 73, 139));
        start.setOnClickListener(v -> showDisclosure(() -> {
            FishingAccessibilityService service = FishingAccessibilityService.active;
            if (service == null) {
                Toast.makeText(this, "먼저 접근성 설정에서 Fishing 도우미를 켜 주세요.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            String conflict = service.getConflictingService();
            if (!conflict.isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("다른 Fishing 자동화 앱 실행 중")
                    .setMessage("기존 Fishing 버전의 접근성 서비스가 동시에 켜져 있습니다. 두 앱의 자동 터치가 충돌할 수 있어요. 기존 버전의 접근성 서비스를 끈 후 새 앱을 시작해 주세요.\n\n" + conflict)
                    .setPositiveButton("접근성 설정 열기", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("취소", null)
                    .show();
                return;
            }
            service.startRunning();
            refresh();
            Toast.makeText(this, "카카오톡 낚시 채팅으로 이동하세요.", Toast.LENGTH_LONG).show();
        }));
        layout.addView(start);

        stop = button("■ 자동 낚시 중지", Color.rgb(109, 46, 60));
        stop.setOnClickListener(v -> {
            FishingAccessibilityService service = FishingAccessibilityService.active;
            if (service != null) service.stopRunning();
            refresh();
        });
        layout.addView(stop);

        TextView guide = text(
            "사용법\n\n① 접근성 권한에서 'Fishing 도우미' 허용\n② 자동 낚시 시작 누르기\n③ 카카오톡 낚시방으로 이동\n④ 멈출 때 이 앱으로 돌아와 중지\n\n" +
            "• 낚시 터치 간격: 2.1~2.3초 사이 랜덤(인식 지연 시 더 길 수 있음)\n" +
            "• '낚시'라고 적힌 회색 버튼만 클릭\n" +
            "• 왼쪽/오른쪽 위치 자동 인식\n" +
            "• '자랑하기 / 놓아준다' 결과 카드만 보이면 이전 메시지로 이동해 낚시 버튼 검색\n" +
            "• 최대 3번 이전 메시지 탐색, 버튼 클릭 뒤 최신 메시지로 복귀\n" +
            "• 낚시 웹페이지(couchfisher.com)가 열리면 X/뒤로 닫기\n" +
            "• 카카오톡에서 열린 사진 뷰어도 뒤로가기로 복귀\n" +
            "• 복귀는 최근 카카오톡 채팅을 확인했을 때만, 최대 3회 시도\n" +
            "• 버튼이 없고 3초 이상 정지하면 이전 메시지에서 재탐색\n" +
            "• 최신 낚시 버튼 우선: 화면에 여러 개 있으면 아래쪽 버튼을 선택\n" +
            "• 옆 버튼 글자가 인식되지 않아도 두 회색 버튼과 가운데 흰 틈을 확인\n" +
            "• 실제 회색 낚시 버튼 + 옆 버튼(강화/대결/자랑하기)을 함께 확인\n" +
            "• 채팅 텍스트 '낚시'는 누르지 않음\n" +
            "• 하단 버튼이 입력창에 가려지면 채팅을 위로 스크롤 후 재탐색\n" +
            "• '낚시' 버튼 발견 시 스크롤을 멈추고 우선 클릭\n" +
            "• OCR 완료 이후에만 터치/스크롤 실행(서로 겹치지 않도록 직렬화)\n" +
            "• 스크롤이 완료되면 0.65초 안정화 후 재탐색\n" +
            "• 화면 상단의 낚시 버튼도 인식, 회색 버튼 후보가 있으면 스크롤보다 재인식 우선\n" +            "• 실제 회색 버튼 줄을 발견하면 해당 부분만 2배 확대해 OCR 재검사\n" +
            "• 회색 버튼 줄은 최대 6.5초만 재인식하고 실패 시 스크롤 복구\n" +
            "• 물고기 도둑 이벤트는 낚시 버튼이 없으면 이전 메시지 탐색\n" +
            "• 가려진 버튼 복구도 제한 횟수 이후에는 재탐색\n" +

            "• 12.5초 이상 작업 정지 시 독립 감시기가 자동 복구\n" +
            "• 다른 Fishing 버전 접근성 서비스가 켜져 있으면 중복 실행 방지\n" +
            "• 버튼을 찾지 못하면 클릭하지 않음\n" +
            "• 화면은 기기 안에서만 분석, 외부 전송 없음\n" +
            "• 사진 뷰어에서는 채팅 복귀용 뒤로가기만 수행\n" +
            "• 다른 일반 앱 화면에서는 누르지 않음\n\n" +
            "※ 게임 운영규칙을 확인하고 사용하세요.",
            15, Color.rgb(181, 190, 205));
        guide.setLineSpacing(dp(5), 1f);
        guide.setPadding(0, dp(26), 0, dp(16));
        layout.addView(guide);
        setContentView(scroll);
        refresh();
    }

    private void showDisclosure(Runnable onAccepted) {
        new AlertDialog.Builder(this)
            .setTitle("접근성 및 화면 분석 동의")
            .setMessage("Fishing 앱은 낚시 버튼 인식, 결과 카드 후 이전 메시지 탐색," +
                " 낚시 웹페이지·사진 뷰어에서 채팅 복귀와 채팅 새로고침을 위해 " +
                "카카오톡에 표시된 화면과 UI 정보를 확인합니다. " +
                "채팅에 보이는 다른 텍스트도 이 과정에서 화면 분석에 포함될 수 있습니다.\n\n" +
                "사용자가 시작을 누른 뒤에만 옆의 다른 버튼과 배경까지 확인된 '낚시' 버튼을 터치하고," +
                " 버튼이 없으면 제한적으로 채팅을 스크롤합니다." +
                " 카카오톡에서 열린 낚시 웹페이지와 사진 뷰어를 확인하면 채팅 복귀를 위해 뒤로가기를 수행합니다. " +
                "화면을 외부 서버로 전송하거나 저장하지 않으며, 기기 내부에서만 분석합니다.\n\n" +
                "중지 버튼을 누르거나 접근성 설정에서 언제든 기능을 끌 수 있습니다. " +
                "이 권한은 민감하므로 앱을 이해하고 동의할 때만 허용해 주세요.")
            .setNegativeButton("동의 안 함", (d, which) -> d.dismiss())
            .setPositiveButton("내용 확인 및 동의", (d, which) -> onAccepted.run())
            .show();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, dp(7), 0, dp(7));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        if (status == null) return;
        FishingAccessibilityService service = FishingAccessibilityService.active;
        boolean enabled = service != null;
        boolean running = enabled && service.isRunning();
        status.setText(!enabled ? "⚪ 접근성 권한을 켜 주세요" : running ? "🟢 실행 중" : "🟡 준비 완료 / 중지 상태");
        details.setText(!enabled
                ? "Fishing 도우미가 비활성 상태입니다."
                : "클릭 " + service.getClickCount() + "회 · 스캔 " + service.getScans() + "회\n" +
                  "미인식 " + service.getUnmatchedScans() + "회 · 캡처 오류 " +
                  service.getCaptureFailures() + "회 · 인식 지연 " + service.getTimeouts() + "회\n" +
                  "복구 스크롤 " + service.getRecoveryCount() + "회 · 웹 X 닫기 " +
                  service.getPopupsClosed() + "회 · 사진 복귀 " +
                  service.getRescuedPhotoViews() + "회 · 웹 뒤로 " +
                  service.getRescuedWebViews() + "회 · 제스처 취소 " +
                  service.getCancelledGestures() + "회\n" +
                  "하단 가림 복구 " + service.getRevealedBottomButtons() +
                  "회 · 텍스트 오인 차단 " + service.getBlockedTextCandidates() + "회\n" +
                  "회색 2버튼 보조 인식 " + service.getVisualPairFallbacks() +
                  "회 · 합쳐진 글자 인식 " + service.getMergedOcrPairs() + "회\n" +
                  "회색 버튼 줄 " + service.getVerifiedGreyPairRows() +
                  "개 · 확대 OCR " + service.getFocusedOcrAttempts() +
                  "회 · 확대 성공 " + service.getFocusedOcrMatches() + "회\n" +
                  "오인식 대기 복구 " + service.getBlockedGreyRowRecoveries() +
                  "회 · 물고기 도둑 대응 " + service.getFishThiefRecoveries() + "회\n" +
                  "완료된 제스처 " + service.getSerializedGestures() +
                  "회 · OCR 후 대기 제스처 " + service.getDeferredGestureActions() + "회\n" +
                  "자동 멈춤 복구 " + service.getWatchdogRecoveries() + "회 · 마지막 간격 " +
                  service.getLastChosenTapIntervalMs() + "ms · 탐색 활동 " +
                  service.getLastProgressAgeSeconds() + "초 전\n" +
                  "최근 복구 원인: " + service.getLastRecoveryReason() + "\n" +
                  service.getCurrentStatus() + "\n" + service.getRecentEvents());
        start.setEnabled(enabled && !running);
        start.setAlpha(enabled && !running ? 1f : 0.5f);
        stop.setEnabled(running);
        stop.setAlpha(running ? 1f : 0.5f);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }
}
