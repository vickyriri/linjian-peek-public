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
        findsCompletedCrossMidnightIdleGap();
        distinguishesBriefPickupFromSustainedUse();
        reportsOpenEndedOvernightIdleGap();
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

    private static void findsCompletedCrossMidnightIdleGap() {
        long midnight = BASE + 6 * HOUR;
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(midnight - 40 * MIN, UsageTimeline.FOREGROUND, "chat.app"));
        e.add(event(midnight - 30 * MIN, UsageTimeline.BACKGROUND, "chat.app"));
        e.add(event(midnight + 6 * HOUR + 36 * MIN, UsageTimeline.FOREGROUND, "clock.app"));
        e.add(event(midnight + 6 * HOUR + 37 * MIN, UsageTimeline.BACKGROUND, "clock.app"));
        UsageTimeline.Result r = UsageTimeline.build(e, midnight - 6 * HOUR, midnight + 8 * HOUR);
        UsageTimeline.OvernightSummary o = UsageTimeline.analyzeOvernight(
                r, midnight - 6 * HOUR, midnight + 8 * HOUR, midnight, midnight + 8 * HOUR);
        yes(o.available(), "overnight gap available");
        yes(o.completed(), "overnight gap completed");
        eq(midnight - 30 * MIN, o.idleStartMs, "last phone activity boundary");
        eq(midnight + 6 * HOUR + 36 * MIN, o.idleEndMs, "first morning pickup boundary");
        eq(7 * HOUR + 6 * MIN, o.idleDurationMs(), "overnight idle duration");
    }

    private static void distinguishesBriefPickupFromSustainedUse() {
        long midnight = BASE + 6 * HOUR;
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(midnight - 20 * MIN, UsageTimeline.FOREGROUND, "video.app"));
        e.add(event(midnight - 10 * MIN, UsageTimeline.BACKGROUND, "video.app"));
        e.add(event(midnight + 6 * HOUR + 36 * MIN, UsageTimeline.FOREGROUND, "clock.app"));
        e.add(event(midnight + 6 * HOUR + 37 * MIN, UsageTimeline.BACKGROUND, "clock.app"));
        e.add(event(midnight + 7 * HOUR + 24 * MIN, UsageTimeline.FOREGROUND, "chat.app"));
        e.add(event(midnight + 7 * HOUR + 34 * MIN, UsageTimeline.BACKGROUND, "chat.app"));
        UsageTimeline.Result r = UsageTimeline.build(e, midnight - 6 * HOUR, midnight + 8 * HOUR);
        UsageTimeline.OvernightSummary o = UsageTimeline.analyzeOvernight(
                r, midnight - 6 * HOUR, midnight + 8 * HOUR, midnight, midnight + 8 * HOUR);
        eq(midnight + 6 * HOUR + 36 * MIN, o.idleEndMs, "brief first pickup retained");
        eq(midnight + 7 * HOUR + 24 * MIN, o.firstSustainedActivityAtMs, "later sustained use detected");
    }

    private static void reportsOpenEndedOvernightIdleGap() {
        long midnight = BASE + 6 * HOUR;
        List<UsageTimeline.Event> e = new ArrayList<>();
        e.add(event(midnight - 30 * MIN, UsageTimeline.FOREGROUND, "book.app"));
        e.add(event(midnight - 20 * MIN, UsageTimeline.BACKGROUND, "book.app"));
        UsageTimeline.Result r = UsageTimeline.build(e, midnight - 6 * HOUR, midnight + 7 * HOUR);
        UsageTimeline.OvernightSummary o = UsageTimeline.analyzeOvernight(
                r, midnight - 6 * HOUR, midnight + 7 * HOUR, midnight, midnight + 8 * HOUR);
        yes(o.available(), "open overnight gap available");
        no(o.completed(), "open overnight gap not completed");
        eq(midnight - 20 * MIN, o.idleStartMs, "open gap starts at last activity");
        eq(midnight + 7 * HOUR, o.idleEndMs, "open gap ends at observation time");
    }

    private static UsageTimeline.Event event(long at, int type, String pkg) {
        return new UsageTimeline.Event(at, type, pkg);
    }

    private static void eq(long expected, long actual, String name) {
        if (expected != actual) throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
    }

    private static void yes(boolean value, String name) {
        if (!value) throw new AssertionError(name + ": expected true");
    }

    private static void no(boolean value, String name) {
        if (value) throw new AssertionError(name + ": expected false");
    }
}
