from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        raise SystemExit(f"pattern not found: {label}")
    if count != 1:
        raise SystemExit(f"pattern not unique ({count}): {label}")
    return text.replace(old, new, 1)


# MainActivity: manual companion cloud-sync switch and pending status.
path = Path("android/app/src/main/java/dev/linjian/peek/MainActivity.java")
source = path.read_text()
source = replace_once(
    source,
    "    private static final long A11Y_CONFIRM_WINDOW_MS = 30000L;\n",
    "    private static final long A11Y_CONFIRM_WINDOW_MS = 30000L;\n"
    '    private static final String PREF_COMPANION_CLOUD_SYNC_ENABLED = "companion_cloud_sync_enabled";\n',
    "MainActivity cloud-sync pref constant",
)
source = replace_once(
    source,
    "    private TextView guidianSummaryText, guidianDetailText, guidianSettingsStatusText, guidianAvatarText, versionStatusText, updateChangelogText, licenseSummaryText;\n",
    "    private TextView guidianSummaryText, guidianDetailText, guidianSettingsStatusText, guidianAvatarText, versionStatusText, updateChangelogText, licenseSummaryText, companionCloudSyncStatusText;\n",
    "MainActivity cloud-sync status field",
)
source = replace_once(
    source,
    "    private CheckBox guidianEnabled, guidianRemoteEnabled, guidianFullscreenEnabled, guidianQuietEnabled, calendarLunarEnabled, calendarRepeatEnabled, calendarBannerEnabled;\n",
    "    private CheckBox guidianEnabled, guidianRemoteEnabled, guidianFullscreenEnabled, guidianQuietEnabled, calendarLunarEnabled, calendarRepeatEnabled, calendarBannerEnabled, companionCloudSyncEnabled;\n",
    "MainActivity cloud-sync checkbox field",
)
source = replace_once(
    source,
    '        loadSettings();\n\n        DebugState.append(this, "掌心窗公开版 v0.3.6.5 已打开");',
    '        loadSettings();\n'
    "        if (companionCloudSyncEnabled != null) "
    "companionCloudSyncEnabled.setChecked(AppPrefs.get(this).getBoolean(PREF_COMPANION_CLOUD_SYNC_ENABLED, false));\n\n"
    '        DebugState.append(this, "掌心窗公开版 v0.3.6.5 已打开");',
    "MainActivity load cloud-sync pref",
)
source = replace_once(
    source,
    "        if (toggleButton != null) toggleButton.setOnClickListener(v -> { if (serviceRunning) stopCompanionService(); else startCompanionService(); });\n",
    "        if (toggleButton != null) toggleButton.setOnClickListener(v -> { if (serviceRunning) stopCompanionService(); else startCompanionService(); });\n"
    "        if (companionCloudSyncEnabled != null) companionCloudSyncEnabled.setOnCheckedChangeListener((buttonView, checked) -> {\n"
    "            AppPrefs.get(this).edit().putBoolean(PREF_COMPANION_CLOUD_SYNC_ENABLED, checked).apply();\n"
    "            lastCompanionSyncAt = 0L;\n"
    "            updateUI();\n"
    '            Toast.makeText(this, checked ? "陪伴页云端同步已开启" : "陪伴页云端同步已关闭，仅使用本地缓存", Toast.LENGTH_SHORT).show();\n'
    "        });\n",
    "MainActivity cloud-sync listener",
)
source = replace_once(
    source,
    "        guidianSummaryText = findViewById(R.id.guidianSummaryText); guidianDetailText = findViewById(R.id.guidianDetailText); guidianSettingsStatusText = findViewById(R.id.guidianSettingsStatusText); guidianAvatarText = findViewById(R.id.guidianAvatarText); versionStatusText = findViewById(R.id.versionStatusText); updateChangelogText = findViewById(R.id.updateChangelogText); licenseSummaryText = findViewById(R.id.licenseSummaryText);\n",
    "        guidianSummaryText = findViewById(R.id.guidianSummaryText); guidianDetailText = findViewById(R.id.guidianDetailText); guidianSettingsStatusText = findViewById(R.id.guidianSettingsStatusText); guidianAvatarText = findViewById(R.id.guidianAvatarText); versionStatusText = findViewById(R.id.versionStatusText); updateChangelogText = findViewById(R.id.updateChangelogText); licenseSummaryText = findViewById(R.id.licenseSummaryText); companionCloudSyncStatusText = findViewById(R.id.companionCloudSyncStatusText);\n",
    "MainActivity bind cloud-sync status",
)
source = replace_once(
    source,
    "        guidianEnabled = findViewById(R.id.guidianEnabled); guidianRemoteEnabled = findViewById(R.id.guidianRemoteEnabled); guidianFullscreenEnabled = findViewById(R.id.guidianFullscreenEnabled); guidianQuietEnabled = findViewById(R.id.guidianQuietEnabled); calendarLunarEnabled = findViewById(R.id.calendarLunarEnabled); calendarRepeatEnabled = findViewById(R.id.calendarRepeatEnabled); calendarBannerEnabled = findViewById(R.id.calendarBannerEnabled);\n",
    "        guidianEnabled = findViewById(R.id.guidianEnabled); guidianRemoteEnabled = findViewById(R.id.guidianRemoteEnabled); guidianFullscreenEnabled = findViewById(R.id.guidianFullscreenEnabled); guidianQuietEnabled = findViewById(R.id.guidianQuietEnabled); calendarLunarEnabled = findViewById(R.id.calendarLunarEnabled); calendarRepeatEnabled = findViewById(R.id.calendarRepeatEnabled); calendarBannerEnabled = findViewById(R.id.calendarBannerEnabled); companionCloudSyncEnabled = findViewById(R.id.companionCloudSyncEnabled);\n",
    "MainActivity bind cloud-sync checkbox",
)
old_sync = """        updateCompanionAnniversary(nearestCalendarEvent(null));
        renderCompanionState(CompanionWindowState.cached(this));
        long now = System.currentTimeMillis();
        if (now - lastCompanionSyncAt > 30_000L && AppPrefs.server(this) != null && !AppPrefs.server(this).trim().isEmpty()) {
            lastCompanionSyncAt = now;
            CompanionWindowState.sync(this, 20, (state, error) -> runOnUiThread(() -> renderCompanionState(state)));
        }
"""
new_sync = """        updateCompanionAnniversary(nearestCalendarEvent(null));
        renderCompanionState(CompanionWindowState.cached(this));
        boolean companionCloudSync = AppPrefs.get(this).getBoolean(PREF_COMPANION_CLOUD_SYNC_ENABLED, false);
        if (companionCloudSyncStatusText != null) {
            companionCloudSyncStatusText.setText("陪伴云同步：" + (companionCloudSync ? "已开启" : "已关闭 · 仅本地")
                    + " · 待同步轨迹 " + ActivityEventStore.pendingCount(this) + " 条");
        }
        long now = System.currentTimeMillis();
        if (companionCloudSync && now - lastCompanionSyncAt > 30_000L && AppPrefs.server(this) != null && !AppPrefs.server(this).trim().isEmpty()) {
            lastCompanionSyncAt = now;
            CompanionWindowState.sync(this, 20, (state, error) -> runOnUiThread(() -> renderCompanionState(state)));
        }
"""
source = replace_once(
    source,
    old_sync,
    new_sync,
    "MainActivity gate CompanionWindowState.sync",
)
path.write_text(source)


# activity_main.xml: visible switch in connection settings.
path = Path("android/app/src/main/res/layout/activity_main.xml")
source = path.read_text()
old_xml = """                    <EditText android:id="@+id/intervalInput" android:layout_width="match_parent" android:layout_height="wrap_content" android:hint="轮询间隔毫秒，默认 1500" android:textSize="11sp" android:inputType="number" android:singleLine="true" />
                    <EditText android:id="@+id/userNameInput"""
new_xml = """                    <EditText android:id="@+id/intervalInput" android:layout_width="match_parent" android:layout_height="wrap_content" android:hint="轮询间隔毫秒，默认 1500" android:textSize="11sp" android:inputType="number" android:singleLine="true" />
                    <CheckBox android:id="@+id/companionCloudSyncEnabled" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="陪伴页云端同步" android:textSize="11sp" android:textColor="#FF553742" android:layout_marginTop="6dp" />
                    <TextView android:id="@+id/companionCloudSyncStatusText" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="陪伴云同步：已关闭 · 仅本地" android:textSize="9sp" android:textColor="#FF9A7583" android:paddingLeft="4dp" android:paddingRight="4dp" android:paddingBottom="4dp" />
                    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="关闭后，掌心窗前台不再自动刷新陪伴云端数据；不影响本地轨迹记录、MCP 服务或启动服务后的离线补传。" android:textSize="9sp" android:textColor="#FF9A7583" android:paddingLeft="4dp" android:paddingRight="4dp" android:paddingBottom="6dp" />
                    <EditText android:id="@+id/userNameInput"""
source = replace_once(source, old_xml, new_xml, "activity_main cloud-sync controls")
path.write_text(source)


# ActivityEventStore: filter launcher noise, protect backlog, expand storage,
# and expose the pending count.
path = Path("android/app/src/main/java/dev/linjian/peek/ActivityEventStore.java")
source = path.read_text()
source = replace_once(
    source,
    "import android.content.Context;\nimport android.content.SharedPreferences;\nimport android.content.pm.ApplicationInfo;\n",
    "import android.content.Context;\nimport android.content.Intent;\nimport android.content.SharedPreferences;\nimport android.content.pm.ApplicationInfo;\nimport android.content.pm.ResolveInfo;\n",
    "ActivityEventStore launcher imports",
)
source = replace_once(
    source,
    '    private static final String KEY_PENDING = "activity_events_pending_v1";\n    private static final int MAX_EVENTS = 500;\n',
    '    private static final String KEY_PENDING = "activity_events_pending_v1";\n'
    '    private static final String KEY_PENDING_COUNT = "activity_events_pending_count_v1";\n'
    "    private static final int MAX_EVENTS = 2000;\n",
    "ActivityEventStore capacity and pending count key",
)
old_add = """            JSONArray old = new JSONArray(p.getString(KEY_EVENTS, "[]"));
            JSONArray kept = new JSONArray();
            kept.put(event);
            for (int i = 0; i < old.length() && kept.length() < MAX_EVENTS; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !event.optString("id").equals(item.optString("id"))) kept.put(item);
            }
            SharedPreferences.Editor editor = p.edit().putString(KEY_EVENTS, kept.toString());
            if (upload) editor.putBoolean(KEY_PENDING, true);
            editor.apply();
"""
new_add = """            JSONArray old = new JSONArray(p.getString(KEY_EVENTS, "[]"));
            java.util.ArrayList<JSONObject> items = new java.util.ArrayList<>();
            items.add(event);
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !event.optString("id").equals(item.optString("id"))) items.add(item);
            }
            trimForStorage(items);
            JSONArray kept = new JSONArray();
            for (JSONObject item : items) kept.put(item);
            int pending = pendingCountOf(kept);
            SharedPreferences.Editor editor = p.edit().putString(KEY_EVENTS, kept.toString())
                    .putBoolean(KEY_PENDING, pending > 0).putInt(KEY_PENDING_COUNT, pending);
            editor.apply();
"""
source = replace_once(source, old_add, new_add, "ActivityEventStore backlog-safe add")
source = replace_once(
    source,
    '        if (pkg.isEmpty() || pkg.equals("com.android.systemui") || pkg.contains("inputmethod")) return;\n'
    "        SharedPreferences p = AppPrefs.get(ctx);\n"
    '        String previous = p.getString(KEY_LAST_PACKAGE, "");\n',
    '        if (pkg.isEmpty() || pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || isHomeLauncher(ctx, pkg)) return;\n'
    "        SharedPreferences p = AppPrefs.get(ctx);\n"
    '        String previous = p.getString(KEY_LAST_PACKAGE, "");\n'
    '        if (isHomeLauncher(ctx, previous)) previous = "";\n',
    "ActivityEventStore filter launcher at source",
)
source = replace_once(
    source,
    '                if (source != null && !source.isEmpty() && !source.equals(e.optString("source"))) continue;\n'
    '                if (todayOnly && !e.optString("local_date", "").equals(today)) continue;\n'
    "                out.put(e);\n",
    '                if (source != null && !source.isEmpty() && !source.equals(e.optString("source"))) continue;\n'
    '                if (todayOnly && !e.optString("local_date", "").equals(today)) continue;\n'
    '                if (isHomeLauncher(ctx, e.optString("package_name", ""))) continue;\n'
    "                out.put(e);\n",
    "ActivityEventStore filter launcher from generic lists",
)
source = replace_once(
    source,
    '                if (todayOnly && !today.equals(e.optString("local_date", ""))) continue;\n'
    '                String source = e.optString("source", ""), type = e.optString("type", "");\n',
    '                if (todayOnly && !today.equals(e.optString("local_date", ""))) continue;\n'
    '                if (isHomeLauncher(ctx, e.optString("package_name", ""))) continue;\n'
    '                String source = e.optString("source", ""), type = e.optString("type", "");\n',
    "ActivityEventStore filter launcher from category lists",
)
old_merge = """            java.util.Collections.sort(items, (a, b) -> Long.compare(timeOf(b), timeOf(a)));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < items.size() && i < MAX_EVENTS; i++) kept.put(items.get(i));
            AppPrefs.get(ctx).edit().putString(KEY_EVENTS, kept.toString()).putBoolean(KEY_PENDING, containsPending(kept)).apply();
"""
new_merge = """            java.util.Collections.sort(items, (a, b) -> Long.compare(timeOf(b), timeOf(a)));
            trimForStorage(items);
            JSONArray kept = new JSONArray();
            for (JSONObject item : items) kept.put(item);
            int pending = pendingCountOf(kept);
            AppPrefs.get(ctx).edit().putString(KEY_EVENTS, kept.toString()).putBoolean(KEY_PENDING, pending > 0).putInt(KEY_PENDING_COUNT, pending).apply();
"""
source = replace_once(source, old_merge, new_merge, "ActivityEventStore backlog-safe remote merge")
old_flush = """            JSONArray after = new JSONArray(AppPrefs.get(ctx).getString(KEY_EVENTS, "[]"));
            AppPrefs.get(ctx).edit().putBoolean(KEY_PENDING, containsPending(after)).apply();
            if (sent > 0) DebugState.append(ctx, "已补传本地轨迹 " + sent + " 条");
"""
new_flush = """            JSONArray after = new JSONArray(AppPrefs.get(ctx).getString(KEY_EVENTS, "[]"));
            int remaining = pendingCountOf(after);
            AppPrefs.get(ctx).edit().putBoolean(KEY_PENDING, remaining > 0).putInt(KEY_PENDING_COUNT, remaining).apply();
            if (sent > 1) DebugState.append(ctx, "已同步离线轨迹 " + sent + " 条");
"""
source = replace_once(source, old_flush, new_flush, "ActivityEventStore quiet realtime logs")
source = replace_once(
    source,
    "            p.edit().putString(KEY_EVENTS, all.toString()).putBoolean(KEY_PENDING, containsPending(all)).apply();\n",
    "            int pending = pendingCountOf(all);\n"
    "            p.edit().putString(KEY_EVENTS, all.toString()).putBoolean(KEY_PENDING, pending > 0).putInt(KEY_PENDING_COUNT, pending).apply();\n",
    "ActivityEventStore maintain pending count after sync",
)
insert_before = "    private static boolean cloudSyncAllowed(Context ctx) {\n"
helpers = """    public static int pendingCount(Context ctx) {
        SharedPreferences p = AppPrefs.get(ctx);
        if (p.contains(KEY_PENDING_COUNT)) return Math.max(0, p.getInt(KEY_PENDING_COUNT, 0));
        try {
            int count = pendingCountOf(new JSONArray(p.getString(KEY_EVENTS, "[]")));
            p.edit().putInt(KEY_PENDING_COUNT, count).putBoolean(KEY_PENDING, count > 0).apply();
            return count;
        } catch (Exception ignored) { return 0; }
    }

    private static int pendingCountOf(JSONArray events) {
        int count = 0;
        if (events == null) return count;
        for (int i = 0; i < events.length(); i++) if (isPending(events.optJSONObject(i))) count++;
        return count;
    }

    private static void trimForStorage(java.util.ArrayList<JSONObject> items) {
        while (items.size() > MAX_EVENTS) {
            int removeAt = -1;
            for (int i = items.size() - 1; i >= 0; i--) {
                if (!isPending(items.get(i))) { removeAt = i; break; }
            }
            if (removeAt < 0) removeAt = items.size() - 1;
            items.remove(removeAt);
        }
    }

    private static boolean isHomeLauncher(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        String value = pkg.trim();
        if ("com.miui.home".equals(value) || "com.android.launcher3".equals(value) || "com.google.android.apps.nexuslauncher".equals(value)) return true;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = ctx.getPackageManager().resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null && value.equals(info.activityInfo.packageName);
        } catch (Exception ignored) { return false; }
    }

"""
source = replace_once(source, insert_before, helpers + insert_before, "ActivityEventStore helpers")
path.write_text(source)

print("Astra Custom v0.2 patch applied successfully.")
