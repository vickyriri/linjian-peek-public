# Astra Custom v0.3 · 查岗 2.0

本版只扩展查岗数据和 MCP 读取体验。旧工具继续保留，不增加聊天内容、输入内容或页面内容采集。

## v0.3.1 跨夜活动补算

`overnight_phone_activity` 使用与今日排行相同的 Android UsageEvents，在每次启动服务后回看“昨晚 18:00 至今天 12:00”的跨夜窗口。它不依赖服务器保存昨晚状态，因此掌心窗和 Render 夜间休眠后仍能在早晨补算。

字段包括：

- `longest_idle_gap`：跨越 00:00–08:00 核心时段的最长 App 无活动区间。
- `last_activity_before_idle`：区间前最后一段可归因 App 活动。
- `first_activity_after_idle`：区间后第一次 App 活动，可能只是短暂摸手机。
- `first_sustained_activity_at`：首次达到“15 分钟内累计使用 5 分钟”的时间。
- `evidence_sessions`：空档前最多 4 段、空档后最多 16 段脱敏 App 轨迹。
- `confidence`：边界完整性与核心时段覆盖情况。

该字段只描述手机活动，不读取页面、聊天或输入内容，也不能直接证明用户已入睡或真正起床。

## 四项核心数据

### 1. 完整排行

生活状态新增 `complete_app_ranking_today`。每项包含：

- `rank`
- `app`
- `package`
- `duration_seconds`
- `minutes`

兼容字段 `top_apps_today` 仍保留，继续返回前五名。

### 2. 每小时分布

`hourly_usage_today` 从本地当天 00:00 到当前小时逐小时返回：

- `screen_minutes`：可归因到具体 App 的使用分钟
- `interactive_minutes`：事件流能确认的亮屏分钟
- `unattributed_minutes`：亮屏但不能可靠归到某个 App 的分钟
- `unlock_count`：该小时解锁次数

解锁优先使用 `KEYGUARD_HIDDEN`，避免把同一次唤醒的 `SCREEN_INTERACTIVE` 重复计算；厂商不提供前者时才回退到亮屏事件，并做 15 秒去重。

### 3. 轨迹区间

`usage_sessions_today` 按最近优先返回 App 使用区间：

- `start_at` / `end_at`
- `duration_seconds` / `minutes`
- `app` / `package`
- `ongoing`

区间由 Android UsageEvents 重建。App 切换、退到后台和息屏都会闭合当前区间；跨零点区间裁切到本地 00:00。最多返回 500 段，是否截断写入可信度字段。

### 4. 数据可信度

`usage_data_trust` 包含：

- `status` 与 `score`
- `attribution_coverage_percent`
- `event_count`
- `attributed_minutes` / `interactive_minutes` / `unattributed_minutes`
- `first_event_at` / `last_event_at`
- `sessions_truncated`
- `notes`

未归因时间不会擅自分配给任何 App。没有读到事件时也会明确提示“空数据不等于确认没有使用手机”。

## MCP 主入口

`get_checkin_snapshot(device_id)` 一次组合返回：

- `current`：当前 App、屏幕、电量、充电与网络
- `today.complete_app_ranking`
- `today.hourly_distribution`
- `today.journey_intervals`
- `overnight`：跨夜最后活动、最长无活动区间、晨间首次/持续使用和证据轨迹
- `data_trust`：同时加入服务器缓存的新鲜度与最后同步时间

手机端仍是旧版时，工具会退回 `top_apps_today` 并把缺失字段列在 `data_trust.missing_fields`，不会伪造完整数据。

为避免把全天轨迹随每个 10 秒心跳反复上传，当前状态仍按原频率更新，较大的排行/小时/轨迹明细每 60 秒更新一次。后端会在同一天内合并两类上报；快照中的 `usage_details_age_seconds` 会明确显示明细新鲜度。

## 兼容边界

- 不删除、不隐藏、不停用任何既有 MCP 工具。
- 不改变旧工具名。
- `top_apps_today`、`screen_time_today_minutes`、`unlock_count_today` 保持可用。
- 不读取聊天内容、输入内容或页面内容。
