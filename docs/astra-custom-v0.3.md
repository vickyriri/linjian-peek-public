# Astra Custom v0.3 · 查岗 2.0

本版只扩展查岗数据和 MCP 读取体验。旧工具继续保留，不增加聊天内容、输入内容或页面内容采集。

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
- `data_trust`：同时加入服务器缓存的新鲜度与最后同步时间

手机端仍是旧版时，工具会退回 `top_apps_today` 并把缺失字段列在 `data_trust.missing_fields`，不会伪造完整数据。

为避免把全天轨迹随每个 10 秒心跳反复上传，当前状态仍按原频率更新，较大的排行/小时/轨迹明细每 60 秒更新一次。后端会在同一天内合并两类上报；快照中的 `usage_details_age_seconds` 会明确显示明细新鲜度。

## 兼容边界

- 不删除、不隐藏、不停用任何既有 MCP 工具。
- 不改变旧工具名。
- `top_apps_today`、`screen_time_today_minutes`、`unlock_count_today` 保持可用。
- 不读取聊天内容、输入内容或页面内容。
