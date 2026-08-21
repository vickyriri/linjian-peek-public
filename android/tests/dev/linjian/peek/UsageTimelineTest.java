package dev.linjian.peek;

import java.util.ArrayList;
import java.util.List;

public final class UsageTimelineTest {
    private static final long MIN = 60_000L;
    private static final long HOUR = 60L * MIN;
    private static final long BASE = 100L * HOUR;

    public static void main(String[] args) {
        switchesAppsAndSplitsHours();
        clipsSessionAtMidnight();
        closesSessionWhenScreenTurnsOff();
        prefersRealUnlocksAndDeduplicatesThem();
        reportsUnattributedInteractiveTime();
        System.out.println("UsageTimelineTest: all assertions passed");
    }

    private static void switchesAppsAndSplitsHours() {
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(BASE + 10 * MIN, UsageTimeline.SCREEN_INTERACTIVE, ""));
        e.add(event(BASE + 50 * MIN, UsageTimeline.FOREGROUND, "app.a"));
        e.add(event(BASE + 70 * MIN, UsageTimeline.FOREGROUND, "app.b"));
        e.add(event(BASE + 100 * MIN, UsageTimeline.BACKGROUND, "app.b"));
        UsageTimeline.Result r = UsageTimeline.build(e, BASE, BASE + 2 * HOUR);
        eq(2, r.sessions.size(), "two sessions");
        eq(20 * MIN, r.packageDurationsMs.get("app.a"), "app a duration");
        eq(30 * MIN, r.packageDurationsMs.get("app.b"), "app b duration");
        eq(10 * MIN, r.hours.get(0).attributedMs, "first hour usage");
        eq(40 * MIN, r.hours.get(1).attributedMs, "second hour usage");
    }

    private static void clipsSessionAtMidnight() {
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(BASE - 20 * MIN, UsageTimeline.FOREGROUND, "app.a"));
        e.add(event(BASE + 15 * MIN, UsageTimeline.BACKGROUND, "app.a"));
        UsageTimeline.Result r = UsageTimeline.build(e, BASE, BASE + HOUR);
        eq(15 * MIN, r.attributedMs, "midnight clipping");
        eq(BASE, r.sessions.get(0).startMs, "session begins at local midnight");
    }

    private static void closesSessionWhenScreenTurnsOff() {
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(BASE + 5 * MIN, UsageTimeline.FOREGROUND, "app.a"));
        e.add(event(BASE + 25 * MIN, UsageTimeline.SCREEN_NON_INTERACTIVE, ""));
        UsageTimeline.Result r = UsageTimeline.build(e, BASE, BASE + HOUR);
        eq(20 * MIN, r.attributedMs, "screen off closes foreground app");
    }

    private static void prefersRealUnlocksAndDeduplicatesThem() {
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(BASE + 5 * MIN, UsageTimeline.SCREEN_INTERACTIVE, ""));
        e.add(event(BASE + 5 * MIN + 1_000L, UsageTimeline.KEYGUARD_HIDDEN, ""));
        e.add(event(BASE + 5 * MIN + 4_000L, UsageTimeline.KEYGUARD_HIDDEN, ""));
        e.add(event(BASE + 30 * MIN, UsageTimeline.SCREEN_INTERACTIVE, ""));
        e.add(event(BASE + 30 * MIN + 2_000L, UsageTimeline.KEYGUARD_HIDDEN, ""));
        UsageTimeline.Result r = UsageTimeline.build(e, BASE, BASE + HOUR);
        eq(2, r.unlockCount, "keyguard events count once per unlock");
        eq(BASE + 30 * MIN + 2_000L, r.lastUnlockAt, "last unlock");
    }

    private static void reportsUnattributedInteractiveTime() {
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(BASE, UsageTimeline.SCREEN_INTERACTIVE, ""));
        e.add(event(BASE + 10 * MIN, UsageTimeline.FOREGROUND, "app.a"));
        e.add(event(BASE + 40 * MIN, UsageTimeline.BACKGROUND, "app.a"));
        e.add(event(BASE + 60 * MIN, UsageTimeline.SCREEN_NON_INTERACTIVE, ""));
        UsageTimeline.Result r = UsageTimeline.build(e, BASE, BASE + HOUR);
        eq(30 * MIN, r.attributedMs, "attributed minutes");
        eq(60 * MIN, r.interactiveMs, "interactive minutes");
        eq(30 * MIN, r.unattributedMs, "unattributed minutes");
        eq(50, r.coveragePercent(), "coverage percent");
    }

    private static UsageTimeline.Event event(long at, int type, String pkg) {
        return new UsageTimeline.Event(at, type, pkg);
    }

    private static void eq(long expected, long actual, String name) {
        if (expected != actual) throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
    }
}
