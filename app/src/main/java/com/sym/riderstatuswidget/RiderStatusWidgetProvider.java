package com.sym.riderstatuswidget;

import android.Manifest;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class RiderStatusWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.sym.riderstatuswidget.REFRESH";

    private static final String PREFS = "rider_status_widget_v01";
    private static final String K_TEMP = "temp";
    private static final String K_MAX = "max";
    private static final String K_MIN = "min";
    private static final String K_CODE = "code";
    private static final String K_NAME = "name";
    private static final String K_AT = "at";
    private static final long WEATHER_TTL = 30L * 60L * 1000L;

    public static void requestRefresh(Context context) {
        Intent i = new Intent(context, RiderStatusWidgetProvider.class);
        i.setAction(ACTION_REFRESH);
        context.sendBroadcast(i);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        render(context, manager, appWidgetIds);
        refreshWeatherAsync(context, appWidgetIds, false, null);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (ACTION_REFRESH.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, RiderStatusWidgetProvider.class));
            render(context, manager, ids);
            android.content.BroadcastReceiver.PendingResult pending = goAsync();
            refreshWeatherAsync(context, ids, true, pending);
            return;
        }
        super.onReceive(context, intent);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;

        PhoneSnapshot phone = readPhoneSnapshot(context);
        WeatherSnapshot weather = readWeatherCache(context);

        for (int id : ids) {
            RemoteViews rv = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_rider_status
            );

            rv.setTextViewText(R.id.widget_weather_icon, weather.icon());
            rv.setTextViewText(R.id.widget_weather_temp, weather.tempText());
            rv.setTextViewText(R.id.widget_weather_location,
                    weather.valid ? weather.placeName : locationHint(context));
            rv.setTextViewText(R.id.widget_weather_condition, weather.condition());
            rv.setTextViewText(R.id.widget_weather_range, weather.rangeText());

            rv.setTextViewText(R.id.widget_phone_state, "폰 상태 " + phone.overallLabel);
            rv.setTextColor(R.id.widget_phone_state, phone.overallColor);
            rv.setTextViewText(
                    R.id.widget_phone_detail,
                    "배터리온도 " + phone.batteryTempText
                            + "  ·  부하 " + phone.loadLabel
                            + "  ·  메모리 " + phone.memoryLabel
            );

            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPi = PendingIntent.getActivity(
                    context,
                    9101,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
            );
            rv.setOnClickPendingIntent(R.id.widget_root, openPi);
            rv.setOnClickPendingIntent(R.id.widget_phone_card, openPi);

            manager.updateAppWidget(id, rv);
        }
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static String locationHint(Context context) {
        return hasLocationPermission(context) ? "날씨 불러오는 중" : "위치 권한 필요";
    }

    public static PhoneSnapshot readPhoneSnapshotForUi(Context context) {
        return readPhoneSnapshot(context);
    }

    private static PhoneSnapshot readPhoneSnapshot(Context context) {
        float batteryTemp = Float.NaN;
        int batteryLevel = -1;

        try {
            Intent battery = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );
            if (battery != null) {
                int rawTemp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (rawTemp >= 0) batteryTemp = rawTemp / 10f;

                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    batteryLevel = Math.max(0, Math.min(100,
                            Math.round(level * 100f / scale)));
                }
            }
        } catch (Exception ignored) {
        }

        int thermal = PowerManager.THERMAL_STATUS_NONE;
        boolean thermalSupported = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager pm =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    thermal = pm.getCurrentThermalStatus();
                    thermalSupported = true;
                }
            } catch (Exception ignored) {
            }
        }

        String memoryLabel = "확인중";
        int memoryLevel = 0;
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                double ratio = mi.totalMem > 0
                        ? (double) mi.availMem / (double) mi.totalMem : 1.0;
                if (mi.lowMemory || ratio < 0.15) {
                    memoryLabel = "주의";
                    memoryLevel = 2;
                } else if (ratio < 0.25) {
                    memoryLabel = "보통";
                    memoryLevel = 1;
                } else {
                    memoryLabel = "정상";
                }
            }
        } catch (Exception ignored) {
        }

        LoadSnapshot load = readLoad();

        int level = Math.max(memoryLevel, load.level);
        if (!Float.isNaN(batteryTemp)) {
            if (batteryTemp >= 43.0f) level = Math.max(level, 2);
            else if (batteryTemp >= 39.5f) level = Math.max(level, 1);
        }

        if (thermalSupported) {
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                level = Math.max(level, 2);
            } else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
                level = Math.max(level, 1);
            }
        }

        String overall;
        int color;
        if (level >= 2) {
            overall = "높음";
            color = Color.rgb(255, 105, 105);
        } else if (level == 1) {
            overall = "주의";
            color = Color.rgb(255, 197, 82);
        } else {
            overall = "정상";
            color = Color.rgb(67, 225, 143);
        }

        String thermalLabel = thermalSupported ? thermalText(thermal) : "지원안됨";

        return new PhoneSnapshot(
                overall,
                color,
                Float.isNaN(batteryTemp)
                        ? "--.-℃"
                        : String.format(Locale.KOREA, "%.1f℃", batteryTemp),
                batteryLevel < 0 ? "--%" : batteryLevel + "%",
                load.label,
                memoryLabel,
                thermalLabel
        );
    }

    private static String thermalText(int thermal) {
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return "높음";
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return "주의";
        if (thermal >= PowerManager.THERMAL_STATUS_LIGHT) return "약간 높음";
        return "정상";
    }

    private static LoadSnapshot readLoad() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream("/proc/loadavg"),
                        StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) return new LoadSnapshot("확인중", 0);
            String[] p = line.trim().split("\\s+");
            if (p.length == 0) return new LoadSnapshot("확인중", 0);

            double oneMinute = Double.parseDouble(p[0]);
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double normalized = oneMinute / cores;

            if (normalized >= 0.85) return new LoadSnapshot("높음", 2);
            if (normalized >= 0.55) return new LoadSnapshot("보통", 1);
            return new LoadSnapshot("낮음", 0);
        } catch (Exception ignored) {
            return new LoadSnapshot("확인중", 0);
        }
    }

    private static boolean hasLocationPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static Location findLastLocation(Context context) {
        if (!hasLocationPermission(context)) return null;

        try {
            LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;

            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (String provider : providers) {
                try {
                    Location candidate = lm.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    if (best == null || candidate.getTime() > best.getTime()) {
                        best = candidate;
                    }
                } catch (SecurityException ignored) {
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void refreshWeatherAsync(
            Context context,
            int[] ids,
            boolean force,
            android.content.BroadcastReceiver.PendingResult pending
    ) {
        final Context app = context.getApplicationContext();
        final int[] targetIds = ids == null ? new int[0] : ids.clone();

        new Thread(() -> {
            try {
                WeatherSnapshot cached = readWeatherCache(app);
                boolean fresh = cached.valid
                        && System.currentTimeMillis() - cached.updatedAt < WEATHER_TTL;
                if (!force && fresh) return;

                Location location = findLastLocation(app);
                if (location == null) return;

                WeatherSnapshot fetched = fetchWeather(app, location);
                if (fetched != null && fetched.valid) saveWeather(app, fetched);
            } catch (Exception ignored) {
            } finally {
                try {
                    render(
                            app,
                            AppWidgetManager.getInstance(app),
                            targetIds
                    );
                } catch (Exception ignored) {
                }
                if (pending != null) pending.finish();
            }
        }, "RiderStatusWidgetWeather").start();
    }

    private static WeatherSnapshot fetchWeather(Context context, Location location) {
        HttpURLConnection c = null;
        try {
            String endpoint = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast"
                            + "?latitude=%.5f&longitude=%.5f"
                            + "&current=temperature_2m,weather_code"
                            + "&daily=temperature_2m_max,temperature_2m_min"
                            + "&forecast_days=1&timezone=auto",
                    location.getLatitude(),
                    location.getLongitude()
            );

            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");

            if (c.getResponseCode() != 200) return null;

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            JSONObject root = new JSONObject(body.toString());
            JSONObject current = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            JSONArray max = daily.getJSONArray("temperature_2m_max");
            JSONArray min = daily.getJSONArray("temperature_2m_min");

            String placeName = reverseGeocode(context, location);

            return new WeatherSnapshot(
                    true,
                    (float) current.getDouble("temperature_2m"),
                    (float) max.getDouble(0),
                    (float) min.getDouble(0),
                    current.getInt("weather_code"),
                    placeName,
                    System.currentTimeMillis()
            );
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String reverseGeocode(Context context, Location location) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.KOREA);
            List<Address> list = geocoder.getFromLocation(
                    location.getLatitude(),
                    location.getLongitude(),
                    1
            );
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                if (a.getSubLocality() != null && !a.getSubLocality().isEmpty()) {
                    return a.getSubLocality();
                }
                if (a.getThoroughfare() != null && !a.getThoroughfare().isEmpty()) {
                    return a.getThoroughfare();
                }
                if (a.getLocality() != null && !a.getLocality().isEmpty()) {
                    return a.getLocality();
                }
            }
        } catch (Exception ignored) {
        }
        return "현재 위치";
    }

    private static WeatherSnapshot readWeatherCache(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = p.getLong(K_AT, 0L);
        if (at <= 0) return WeatherSnapshot.empty();

        return new WeatherSnapshot(
                true,
                p.getFloat(K_TEMP, Float.NaN),
                p.getFloat(K_MAX, Float.NaN),
                p.getFloat(K_MIN, Float.NaN),
                p.getInt(K_CODE, -1),
                p.getString(K_NAME, "현재 위치"),
                at
        );
    }

    private static void saveWeather(Context context, WeatherSnapshot weather) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(K_TEMP, weather.temp)
                .putFloat(K_MAX, weather.max)
                .putFloat(K_MIN, weather.min)
                .putInt(K_CODE, weather.code)
                .putString(K_NAME, weather.placeName)
                .putLong(K_AT, weather.updatedAt)
                .apply();
    }

    private static final class LoadSnapshot {
        final String label;
        final int level;

        LoadSnapshot(String label, int level) {
            this.label = label;
            this.level = level;
        }
    }

    public static final class PhoneSnapshot {
        public final String overallLabel;
        public final int overallColor;
        public final String batteryTempText;
        public final String batteryLevelText;
        public final String loadLabel;
        public final String memoryLabel;
        public final String thermalLabel;

        PhoneSnapshot(
                String overallLabel,
                int overallColor,
                String batteryTempText,
                String batteryLevelText,
                String loadLabel,
                String memoryLabel,
                String thermalLabel
        ) {
            this.overallLabel = overallLabel;
            this.overallColor = overallColor;
            this.batteryTempText = batteryTempText;
            this.batteryLevelText = batteryLevelText;
            this.loadLabel = loadLabel;
            this.memoryLabel = memoryLabel;
            this.thermalLabel = thermalLabel;
        }
    }

    private static final class WeatherSnapshot {
        final boolean valid;
        final float temp;
        final float max;
        final float min;
        final int code;
        final String placeName;
        final long updatedAt;

        WeatherSnapshot(
                boolean valid,
                float temp,
                float max,
                float min,
                int code,
                String placeName,
                long updatedAt
        ) {
            this.valid = valid;
            this.temp = temp;
            this.max = max;
            this.min = min;
            this.code = code;
            this.placeName = placeName;
            this.updatedAt = updatedAt;
        }

        static WeatherSnapshot empty() {
            return new WeatherSnapshot(
                    false,
                    Float.NaN,
                    Float.NaN,
                    Float.NaN,
                    -1,
                    "위치 권한 필요",
                    0L
            );
        }

        String tempText() {
            return valid && !Float.isNaN(temp) ? Math.round(temp) + "°" : "--°";
        }

        String rangeText() {
            if (!valid || Float.isNaN(max) || Float.isNaN(min)) {
                return "↑--° / ↓--°";
            }
            return "↑" + Math.round(max) + "° / ↓" + Math.round(min) + "°";
        }

        String condition() {
            if (!valid) return "날씨 대기";
            if (code == 0) return "맑음";
            if (code == 1 || code == 2) return "구름 조금";
            if (code == 3) return "흐림";
            if (code == 45 || code == 48) return "안개";
            if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "비";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "눈";
            if (code >= 95) return "뇌우";
            return "날씨";
        }

        String icon() {
            if (!valid) return "☁";
            if (code == 0) return "☀";
            if (code == 1 || code == 2) return "⛅";
            if (code == 3) return "☁";
            if (code == 45 || code == 48) return "≋";
            if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";
            if (code >= 95) return "ϟ";
            return "☁";
        }
    }
}
