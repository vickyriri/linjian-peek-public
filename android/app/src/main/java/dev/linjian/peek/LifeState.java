package dev.linjian.peek;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** 轻量生活状态层：不截图，不读聊天内容，只上传设备状态。 */
public class LifeState {
    private static final Object USAGE_CACHE_LOCK = new Object();
    private static final long USAGE_CACHE_MS = 15_000L;
    private static volatile UsageSummary cachedUsage;
    private static volatile long cachedUsageAt;
    private static volatile String cachedUsageDate = "";

    public static JSONObject collect(Context ctx) {
        return collect(ctx, true);
    }

    public static JSONObject collect(Context ctx, boolean includeUsageDetails) {
        JSONObject state = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            Intent battery = ctx.registerReceiver((BroadcastReceiver) null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int batteryPercent = -1;
            boolean charging = false;
            String chargingType = "unknown";
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPercent = Math.round(level * 100f / scale);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                chargingType = pluggedToString(plugged);
                state.put("battery_status", batteryStatusToString(status));
            }

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm != null && (Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn());
            String currentPackage = ScreenshotService.currentPackage();
            String currentApp = appLabel(ctx, currentPackage);
            boolean usageReady = hasUsagePermission(ctx);
            UsageSummary usage = usageReady ? readUsageCached(ctx, now) : UsageSummary.unavailable(now);
            SharedPreferencesCompat prefs = new SharedPreferencesCompat(ctx);

            state.put("device_id", AppPrefs.device(ctx));
            state.put("life_state_version", "0.3.6.6-astra-checkin-v1");
            state.put("local_time", formatLocal(now, "HH:mm"));
            state.put("local_date", formatLocal(now, "yyyy-MM-dd"));
            state.put("timezone", TimeZone.getDefault().getID());
            state.put("updated_at_ms", now);
            state.put("updated_at_local", formatLocal(now, "yyyy-MM-dd HH:mm:ss"));
            state.put("battery_percent", batteryPercent);
            state.put("charging", charging);
            state.put("charging_type", chargingType);
            state.put("network_type", networkType(ctx));
            state.put("screen_on", screenOn);
            state.put("current_package", currentPackage);
            state.put("current_app", currentApp);
            state.put("accessibility_ready", ScreenshotService.ready());
            state.put("usage_permission_ready", usageReady);
            state.put("screen_time_today_minutes", usage.screenTimeMinutes);
            state.put("unlock_count_today", usage.unlockCount);
            state.put("last_unlock_at", usage.lastUnlockAt <= 0 ? "" : formatIsoLocal(usage.lastUnlockAt));
            state.put("top_apps_today", usage.topApps);
            if (includeUsageDetails) {
                state.put("complete_app_ranking_today", usage.completeRanking);
                state.put("hourly_usage_today", usage.hourlyUsage);
                state.put("usage_sessions_today", usage.sessions);
                state.put("usage_data_trust", usage.dataTrust);
                state.put("usage_details_updated_at_ms", now);
                state.put("usage_details_updated_at", formatIsoLocal(now));
            }
            state.put("city", prefs.city());
            state.put("weather_note", prefs.weatherNote());
            JSONObject weatherState = WeatherState.collect(ctx);
            state.put("weather_state", weatherState);
            state.put("weather_locations", weatherState.optJSONArray("locations"));
            state.put("current_weather_location", weatherState.optJSONObject("current"));
            state.put("screen_text", ScreenshotService.screenText());
            state.put("active_reminders", ActiveReminder.config(ctx));
            state.put("home_mode", HomeMode.config(ctx));
            state.put("known_apps", AppPrefs.knownAppsJson(ctx));
            state.put("app_gate", AppGate.config(ctx));
            state.put("cycle_state", CycleState.collect(ctx));
            state.put("calendar_state", CalendarState.collect(ctx));
            state.put("guidian_state", GuidianState.config(ctx));
            state.put("summary", makeSummary(batteryPercent, charging, currentApp, usage.screenTimeMinutes, usage.unlockCount, usageReady));
        } catch (Exception e) {
            try { state.put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return state;
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject s = collect(ctx);
            StringBuilder sb = new StringBuilder();
            sb.append("生活状态层 v0.3.6.6 · Astra 查岗 2.0\n");
            sb.append("时间：").append(s.optString("local_time", "-")).append("  ").append(s.optString("local_date", "-")).append("\n");
            sb.append("电量：").append(s.optInt("battery_percent", -1)).append("%  ").append(s.optBoolean("charging") ? "充电中" : "未充电").append("\n");
            sb.append("网络：").append(s.optString("network_type", "-")).append("  屏幕：").append(s.optBoolean("screen_on") ? "亮" : "灭").append("\n");
            sb.append("当前：").append(s.optString("current_app", "-")).append("\n");
            sb.append("屏幕时间：").append(s.optInt("screen_time_today_minutes", 0)).append(" 分钟  解锁：").append(s.optInt("unlock_count_today", 0)).append(" 次\n");
            sb.append("使用权限：").append(s.optBoolean("usage_permission_ready") ? "已开启" : "未开启，屏幕时间/解锁次数会为空").append("\n");
            JSONObject trust = s.optJSONObject("usage_data_trust");
            if (trust != null) sb.append("数据可信度：").append(trust.optInt("score", 0)).append("/100 · ").append(trust.optString("status", "unknown")).append(" · 未归因 ").append(trust.optDouble("unattributed_minutes", 0)).append(" 分钟\n");
            String city = s.optString("city", "");
            String weather = s.optString("weather_note", "");
            if (!city.isEmpty() || !weather.isEmpty()) sb.append("城市/天气：").append(city).append(city.isEmpty() || weather.isEmpty() ? "" : " · ").append(weather).append("\n");
            sb.append("\n").append(WeatherState.pretty(ctx));
            sb.append("\n").append(s.optString("summary", ""));
            sb.append("\n\n").append(ActiveReminder.pretty(ctx));
            sb.append("\n\n").append(HomeMode.pretty(ctx));
            sb.append("\n\n").append(AppGate.pretty(ctx));
            sb.append("\n\n可打开 App：\n").append(AppPrefs.knownAppsText(ctx));
            sb.append("\n\n").append(CycleState.pretty(ctx));
            sb.append("\n\n").append(CalendarState.pretty(ctx));
            return sb.toString();
        } catch (Exception e) { return "生活状态读取失败：" + ScreenshotService.shortMsg(e); }
    }

    private static String makeSummary(int battery, boolean charging, String app, int screenMinutes, int unlocks, boolean usageReady) {
        StringBuilder sb = new StringBuilder();
        sb.append("轻量查岗：");
        if (app != null && app.length() > 0) sb.append("当前在 ").append(app).append("；");
        if (battery >= 0) sb.append("电量 ").append(battery).append("%").append(charging ? "，正在充电；" : "，未充电；");
        if (usageReady) sb.append("今日屏幕约 ").append(screenMinutes).append(" 分钟，解锁 ").append(unlocks).append(" 次。");
        else sb.append("使用情况权限未开，暂时看不到今日屏幕时间。");
        return sb.toString();
    }

    private static String pluggedToString(int plugged) {
        if (plugged == BatteryManager.BATTERY_PLUGGED_USB) return "usb";
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) return "ac";
        if (Build.VERSION.SDK_INT >= 17 && plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) return "wireless";
        return "none";
    }

    private static String batteryStatusToString(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            default: return "unknown";
        }
    }

    private static String networkType(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return "none";
                NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                if (cap == null) return "unknown";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
                return "other";
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) return "none";
                return info.getTypeName().toLowerCase(Locale.US);
            }
        } catch (Exception e) { return "unknown"; }
    }

    public static boolean hasUsagePermission(Context ctx) {
        try {
            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private static UsageSummary readUsage(Context ctx, long now) {
        UsageSummary summary = new UsageSummary();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        long queryStart = start - 24L * 60L * 60L * 1000L;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) throw new IllegalStateException("usage_stats_manager_unavailable");
            List<UsageTimeline.Event> raw = new ArrayList<>();
            UsageEvents events = usm.queryEvents(queryStart, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (!isUsageEvent(type)) continue;
                String pkg = event.getPackageName();
                if (isIgnoredPackage(ctx, pkg)) pkg = "";
                raw.add(new UsageTimeline.Event(event.getTimeStamp(), type, pkg));
            }
            UsageTimeline.Result timeline = UsageTimeline.build(raw, start, now);
            fillUsageJson(ctx, summary, timeline, start, queryStart, now);
        } catch (Exception error) {
            summary.dataTrust = trustJson(false, start, queryStart, now, null, ScreenshotService.shortMsg(error));
        }
        return summary;
    }

    private static UsageSummary readUsageCached(Context ctx, long now) {
        String date = formatLocal(now, "yyyy-MM-dd");
        UsageSummary hit = cachedUsage;
        if (hit != null && date.equals(cachedUsageDate) && now - cachedUsageAt >= 0L && now - cachedUsageAt < USAGE_CACHE_MS) return hit;
        synchronized (USAGE_CACHE_LOCK) {
            hit = cachedUsage;
            if (hit != null && date.equals(cachedUsageDate) && now - cachedUsageAt >= 0L && now - cachedUsageAt < USAGE_CACHE_MS) return hit;
            UsageSummary fresh = readUsage(ctx, now);
            cachedUsage = fresh;
            cachedUsageAt = now;
            cachedUsageDate = date;
            return fresh;
        }
    }

    private static boolean isUsageEvent(int type) {
        return type == UsageTimeline.FOREGROUND || type == UsageTimeline.BACKGROUND
                || type == UsageTimeline.SCREEN_INTERACTIVE || type == UsageTimeline.SCREEN_NON_INTERACTIVE
                || type == UsageTimeline.KEYGUARD_HIDDEN;
    }

    private static void fillUsageJson(Context ctx, UsageSummary summary, UsageTimeline.Result timeline, long start, long queryStart, long now) throws Exception {
        summary.screenTimeMinutes = (int) Math.round(timeline.attributedMs / 60000.0);
        summary.unlockCount = timeline.unlockCount;
        summary.lastUnlockAt = timeline.lastUnlockAt;

        Map<String, String> labels = new HashMap<>();
        List<AppUse> apps = new ArrayList<>();
        for (Map.Entry<String, Long> entry : timeline.packageDurationsMs.entrySet()) {
            String pkg = entry.getKey();
            String label = labelFor(ctx, labels, pkg);
            apps.add(new AppUse(label, pkg, entry.getValue()));
        }
        Collections.sort(apps, new Comparator<AppUse>() { @Override public int compare(AppUse a, AppUse b) { return Long.compare(b.ms, a.ms); } });
        for (int i = 0; i < apps.size(); i++) {
            AppUse use = apps.get(i);
            JSONObject item = usageItem(use, i + 1);
            summary.completeRanking.put(item);
            if (i < 5) summary.topApps.put(new JSONObject(item.toString()));
        }

        for (UsageTimeline.HourBucket hour : timeline.hours) {
            JSONObject item = new JSONObject();
            item.put("hour", formatLocal(hour.startMs, "HH:00"));
            item.put("start_at", formatIsoLocal(hour.startMs));
            item.put("end_at", formatIsoLocal(hour.endMs));
            item.put("screen_minutes", minutesOneDecimal(hour.attributedMs));
            item.put("interactive_minutes", minutesOneDecimal(hour.interactiveMs));
            item.put("unattributed_minutes", minutesOneDecimal(Math.max(0L, hour.interactiveMs - hour.attributedMs)));
            item.put("unlock_count", hour.unlockCount);
            summary.hourlyUsage.put(item);
        }

        final int maxSessions = 500;
        int first = Math.max(0, timeline.sessions.size() - maxSessions);
        for (int i = timeline.sessions.size() - 1; i >= first; i--) {
            UsageTimeline.Session session = timeline.sessions.get(i);
            JSONObject item = new JSONObject();
            item.put("app", labelFor(ctx, labels, session.packageName));
            item.put("package", session.packageName);
            item.put("start_at", formatIsoLocal(session.startMs));
            item.put("end_at", formatIsoLocal(session.endMs));
            item.put("duration_seconds", Math.round(session.durationMs() / 1000.0));
            item.put("minutes", minutesOneDecimal(session.durationMs()));
            item.put("ongoing", session.endMs >= now - 1_000L);
            summary.sessions.put(item);
        }
        summary.dataTrust = trustJson(true, start, queryStart, now, timeline, "");
    }

    private static JSONObject usageItem(AppUse use, int rank) throws Exception {
        JSONObject item = new JSONObject();
        item.put("rank", rank);
        item.put("app", use.label);
        item.put("package", use.pkg);
        item.put("duration_seconds", Math.round(use.ms / 1000.0));
        item.put("minutes", minutesOneDecimal(use.ms));
        return item;
    }

    private static JSONObject trustJson(boolean ok, long start, long queryStart, long now, UsageTimeline.Result timeline, String error) {
        JSONObject trust = new JSONObject();
        try {
            trust.put("available", ok);
            trust.put("source", "android_usage_events_sessionized");
            trust.put("query_succeeded", ok);
            trust.put("window_start_at", formatIsoLocal(start));
            trust.put("window_end_at", formatIsoLocal(now));
            trust.put("lookback_start_at", formatIsoLocal(queryStart));
            if (!ok || timeline == null) {
                trust.put("status", "unavailable");
                trust.put("score", 0);
                trust.put("error", error == null ? "" : error);
                trust.put("notes", new JSONArray().put("未能读取 Android 使用情况事件；排行、小时分布和轨迹区间不可验证。"));
                return trust;
            }
            int coverage = timeline.coveragePercent();
            int score = timeline.eventCount == 0 ? 45 : Math.min(100, 70 + Math.round(coverage * 0.30f));
            String status = score >= 85 ? "high" : (score >= 60 ? "medium" : "low");
            trust.put("status", status);
            trust.put("score", score);
            trust.put("attribution_coverage_percent", coverage);
            trust.put("event_count", timeline.eventCount);
            trust.put("first_event_at", timeline.firstEventAt <= 0 ? "" : formatIsoLocal(timeline.firstEventAt));
            trust.put("last_event_at", timeline.lastEventAt <= 0 ? "" : formatIsoLocal(timeline.lastEventAt));
            trust.put("attributed_minutes", minutesOneDecimal(timeline.attributedMs));
            trust.put("interactive_minutes", minutesOneDecimal(timeline.interactiveMs));
            trust.put("unattributed_minutes", minutesOneDecimal(timeline.unattributedMs));
            trust.put("session_count", timeline.sessions.size());
            trust.put("sessions_returned", Math.min(500, timeline.sessions.size()));
            trust.put("sessions_truncated", timeline.sessions.size() > 500);
            JSONArray notes = new JSONArray();
            notes.put("只统计 App 名称和使用区间，不读取聊天、输入或页面内容。");
            if (timeline.unattributedMs > 0) notes.put("未归因时间通常来自桌面、系统界面或厂商漏报；不会擅自归到某个 App。");
            if (timeline.eventCount == 0) notes.put("今天尚未读到可验证事件，空数据不等于确认没有使用手机。");
            trust.put("notes", notes);
        } catch (Exception ignored) { }
        return trust;
    }

    private static double minutesOneDecimal(long ms) {
        return Math.round(ms / 6000.0) / 10.0;
    }

    private static String labelFor(Context ctx, Map<String, String> labels, String pkg) {
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String label = appLabel(ctx, pkg);
        labels.put(pkg, label);
        return label;
    }

    private static boolean isIgnoredPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return true;
        String value = pkg.trim();
        if ("com.android.systemui".equals(value) || value.contains("inputmethod")) return true;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo home = ctx.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return home != null && home.activityInfo != null && value.equals(home.activityInfo.packageName);
        } catch (Exception ignored) { return false; }
    }

    private static String appLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (Exception e) { return pkg; }
    }

    private static String formatLocal(long ms, String pattern) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(ms));
    }

    private static String formatIsoLocal(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }

    private static class UsageSummary {
        int screenTimeMinutes = 0;
        int unlockCount = 0;
        long lastUnlockAt = 0;
        JSONArray topApps = new JSONArray();
        JSONArray completeRanking = new JSONArray();
        JSONArray hourlyUsage = new JSONArray();
        JSONArray sessions = new JSONArray();
        JSONObject dataTrust = new JSONObject();

        static UsageSummary unavailable(long now) {
            UsageSummary summary = new UsageSummary();
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            summary.dataTrust = trustJson(false, cal.getTimeInMillis(), cal.getTimeInMillis(), now, null, "usage_permission_not_granted");
            return summary;
        }
    }

    private static class AppUse {
        final String label; final String pkg; final long ms;
        AppUse(String label, String pkg, long ms) { this.label = label; this.pkg = pkg; this.ms = ms; }
    }

    private static class SharedPreferencesCompat {
        private final Context ctx;
        SharedPreferencesCompat(Context ctx) { this.ctx = ctx; }
        String city() { return AppPrefs.get(ctx).getString(AppPrefs.KEY_CITY, ""); }
        String weatherNote() { return AppPrefs.get(ctx).getString(AppPrefs.KEY_WEATHER_NOTE, ""); }
    }
}
