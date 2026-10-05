package com.sym.riderstatuswidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 4101;

    private TextView permissionState;
    private TextView phoneState;
    private Button permissionButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        permissionState = findViewById(R.id.permission_state);
        phoneState = findViewById(R.id.phone_state);
        permissionButton = findViewById(R.id.permission_button);

        permissionButton.setOnClickListener(v -> requestLocationIfNeeded());

        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            RiderStatusWidgetProvider.requestRefresh(this);
            updateStatus();
        });

        findViewById(R.id.widget_settings_button).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
                startActivity(intent);
            } catch (Exception ignored) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                startActivity(intent);
            }
        });

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationIfNeeded() {
        if (hasLocation()) {
            RiderStatusWidgetProvider.requestRefresh(this);
            updateStatus();
            return;
        }
        requestPermissions(
                new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQ_LOCATION
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            RiderStatusWidgetProvider.requestRefresh(this);
            updateStatus();
        }
    }

    private void updateStatus() {
        boolean location = hasLocation();
        permissionState.setText(location
                ? "날씨 위치 권한: 허용됨"
                : "날씨 위치 권한: 필요");
        permissionState.setTextColor(location
                ? Color.rgb(55, 214, 137)
                : Color.rgb(255, 184, 77));

        permissionButton.setText(location ? "날씨 새로고침" : "위치 권한 허용");

        RiderStatusWidgetProvider.PhoneSnapshot p =
                RiderStatusWidgetProvider.readPhoneSnapshotForUi(this);

        phoneState.setText(
                "폰 상태 " + p.overallLabel
                        + "\n배터리온도 " + p.batteryTempText
                        + " · 부하 " + p.loadLabel
                        + " · 메모리 " + p.memoryLabel
                        + "\n열상태 " + p.thermalLabel
                        + " · 배터리 " + p.batteryLevelText
        );

        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(this, RiderStatusWidgetProvider.class));
        TextView widgetState = findViewById(R.id.widget_state);
        widgetState.setText(ids.length > 0
                ? "홈 화면 위젯: " + ids.length + "개 사용 중"
                : "홈 화면 위젯: 아직 추가되지 않음");
    }
}
