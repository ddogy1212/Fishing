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
            " '낚시'와 팝업의 X 버튼을 인식하고 화면을 자동 터치합니다." +
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
            "• 인식 주기: 1.8~2.2초 랜덤(처리 시간은 별도)\n" +
            "• '낚시'라고 적힌 회색 버튼만 클릭\n" +
            "• 왼쪽/오른쪽 위치 자동 인식\n" +
            "• 버튼을 찾지 못하면 클릭하지 않음\n" +
            "• 화면은 기기 안에서만 분석, 외부 전송 없음\n" +
            "• 카카오톡 외 다른 앱은 누르지 않음\n\n" +
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
            .setMessage("Fishing 앱은 낚시 버튼과 팝업 닫기 버튼을 찾기 위해 " +
                "카카오톡에 표시된 화면과 UI 정보를 확인합니다. " +
                "채팅에 보이는 다른 텍스트도 이 과정에서 화면 분석에 포함될 수 있습니다.\n\n" +
                "사용자가 시작을 누른 뒤에만 '낚시' 또는 확인된 팝업 닫기 버튼을 자동 터치합니다. " +
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
                : "클릭 " + service.getClickCount() + "회\n" + service.getCurrentStatus());
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
