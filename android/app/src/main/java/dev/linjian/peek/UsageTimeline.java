package dev.linjian.peek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java usage event sessionizer. Android-specific event collection and app labels stay in
 * {@link LifeState}; keeping the timeline logic free of Android dependencies makes the tricky
 * midnight/screen-off/unlock rules directly testable.
 */
public final class UsageTimeline {
    public static final int FOREGROUND = 1;
    public static final int BACKGROUND = 2;
    public static final int SCREEN_INTERACTIVE = 15;
    public static final int SCREEN_NON_INTERACTIVE = 16;
    public static final int KEYGUARD_HIDDEN = 18;

    private static final long SESSION_MERGE_GAP_MS = 2_000L;
    private static final long UNLOCK_DEDUPE_MS = 15_000L;

    private UsageTimeline() { }

    public static final class Event {
        public final long atMs;
        public final int type;
        public final String packageName;

        public Event(long atMs, int type, String packageName) {
            this.atMs = atMs;
            this.type = type;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    public static final class Session {
        public long startMs;
        public long endMs;
        public final String packageName;

        Session(long startMs, long endMs, String packageName) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.packageName = packageName;
        }

        public long durationMs() { return Math.max(0L, endMs - startMs); }
    }

    public static final class HourBucket {
        public final long startMs;
        public final long endMs;
        public long attributedMs;
        public long interactiveMs;
        public int unlockCount;

        HourBucket(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class Span {
        final long startMs;
        final long endMs;

        Span(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class Result {
        public final List<Session> sessions = new ArrayList<>();
        public final Map<String, Long> packageDurationsMs = new LinkedHashMap<>();
        public final List<HourBucket> hours = new ArrayList<>();
        public long attributedMs;
        public long interactiveMs;
        public long unattributedMs;
        public int unlockCount;
        public long lastUnlockAt;
        public int eventCount;
        public long firstEventAt;
        public long lastEventAt;

        public int coveragePercent() {
            if (interactiveMs <= 0L) return attributedMs <= 0L ? 100 : 0;
            return (int) Math.max(0L, Math.min(100L, Math.round(attributedMs * 100.0 / interactiveMs)));
        }
    }

    public static Result build(List<Event> input, long windowStart, long windowEnd) {
        Result result = new Result();
        if (windowEnd <= windowStart) return result;

        List<Event> events = new ArrayList<>(input == null ? Collections.emptyList() : input);
        Collections.sort(events, new Comparator<Event>() {
            @Override public int compare(Event a, Event b) { return Long.compare(a.atMs, b.atMs); }
        });

        String activePackage = "";
        long activeSince = 0L;
        boolean interactiveKnown = false;
        boolean interactive = false;
        long interactiveSince = 0L;
        List<Span> interactiveSpans = new ArrayList<>();
        List<Long> keyguardUnlocks = new ArrayList<>();
        List<Long> screenWakes = new ArrayList<>();

        for (Event event : events) {
            if (event.atMs > windowEnd) break;
            if (event.atMs >= windowStart) {
                result.eventCount++;
                if (result.firstEventAt == 0L) result.firstEventAt = event.atMs;
                result.lastEventAt = event.atMs;
            }

            if (event.type == SCREEN_INTERACTIVE) {
                if (!interactive) interactiveSince = event.atMs;
                interactiveKnown = true;
                interactive = true;
                if (event.atMs >= windowStart) screenWakes.add(event.atMs);
                continue;
            }

            if (event.type == SCREEN_NON_INTERACTIVE) {
                closeSession(result.sessions, activeSince, event.atMs, activePackage, windowStart, windowEnd);
                activePackage = "";
                activeSince = 0L;
                if (interactive) closeSpan(interactiveSpans, interactiveSince, event.atMs, windowStart, windowEnd);
                interactiveKnown = true;
                interactive = false;
                interactiveSince = 0L;
                continue;
            }

            if (event.type == KEYGUARD_HIDDEN) {
                if (event.atMs >= windowStart) keyguardUnlocks.add(event.atMs);
                continue;
            }

            if (event.type == FOREGROUND) {
                if (!activePackage.equals(event.packageName)) {
                    closeSession(result.sessions, activeSince, event.atMs, activePackage, windowStart, windowEnd);
                    activePackage = event.packageName;
                    activeSince = event.atMs;
                }
                // Some vendors omit screen events. A foreground transition still proves that the
                // display was interactive from this point, but we do not invent time before it.
                if (!interactive) {
                    interactiveKnown = true;
                    interactive = true;
                    interactiveSince = event.atMs;
                }
                continue;
            }

            if (event.type == BACKGROUND && activePackage.equals(event.packageName)) {
                closeSession(result.sessions, activeSince, event.atMs, activePackage, windowStart, windowEnd);
                activePackage = "";
                activeSince = 0L;
            }
        }

        closeSession(result.sessions, activeSince, windowEnd, activePackage, windowStart, windowEnd);
        if (interactiveKnown && interactive) closeSpan(interactiveSpans, interactiveSince, windowEnd, windowStart, windowEnd);

        for (Session session : result.sessions) {
            long duration = session.durationMs();
            result.attributedMs += duration;
            Long old = result.packageDurationsMs.get(session.packageName);
            result.packageDurationsMs.put(session.packageName, (old == null ? 0L : old) + duration);
        }
        for (Span span : interactiveSpans) result.interactiveMs += Math.max(0L, span.endMs - span.startMs);
        // App sessions are stronger evidence than missing vendor-specific screen events.
        if (result.interactiveMs < result.attributedMs) result.interactiveMs = result.attributedMs;
        result.unattributedMs = Math.max(0L, result.interactiveMs - result.attributedMs);

        List<Long> unlocks = dedupe(keyguardUnlocks.isEmpty() ? screenWakes : keyguardUnlocks);
        result.unlockCount = unlocks.size();
        if (!unlocks.isEmpty()) result.lastUnlockAt = unlocks.get(unlocks.size() - 1);
        buildHours(result, interactiveSpans, unlocks, windowStart, windowEnd);
        return result;
    }

    private static void closeSession(List<Session> sessions, long rawStart, long rawEnd, String pkg, long windowStart, long windowEnd) {
        if (pkg == null || pkg.isEmpty() || rawStart <= 0L || rawEnd <= rawStart) return;
        long start = Math.max(rawStart, windowStart);
        long end = Math.min(rawEnd, windowEnd);
        if (end <= start) return;
        Session last = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        if (last != null && last.packageName.equals(pkg) && start - last.endMs <= SESSION_MERGE_GAP_MS) {
            last.endMs = Math.max(last.endMs, end);
        } else {
            sessions.add(new Session(start, end, pkg));
        }
    }

    private static void closeSpan(List<Span> spans, long rawStart, long rawEnd, long windowStart, long windowEnd) {
        if (rawStart < 0L || rawEnd <= rawStart) return;
        long start = Math.max(rawStart, windowStart);
        long end = Math.min(rawEnd, windowEnd);
        if (end > start) spans.add(new Span(start, end));
    }

    private static List<Long> dedupe(List<Long> timestamps) {
        List<Long> out = new ArrayList<>();
        for (Long at : timestamps) {
            if (at == null) continue;
            if (out.isEmpty() || at - out.get(out.size() - 1) >= UNLOCK_DEDUPE_MS) out.add(at);
        }
        return out;
    }

    private static void buildHours(Result result, List<Span> interactiveSpans, List<Long> unlocks, long start, long end) {
        final long hourMs = 60L * 60L * 1000L;
        for (long hourStart = start; hourStart < end; hourStart += hourMs) {
            long hourEnd = Math.min(end, hourStart + hourMs);
            HourBucket bucket = new HourBucket(hourStart, hourEnd);
            for (Session session : result.sessions) bucket.attributedMs += overlap(session.startMs, session.endMs, hourStart, hourEnd);
            for (Span span : interactiveSpans) bucket.interactiveMs += overlap(span.startMs, span.endMs, hourStart, hourEnd);
            if (bucket.interactiveMs < bucket.attributedMs) bucket.interactiveMs = bucket.attributedMs;
            for (Long unlock : unlocks) if (unlock >= hourStart && unlock < hourEnd) bucket.unlockCount++;
            result.hours.add(bucket);
        }
    }

    private static long overlap(long aStart, long aEnd, long bStart, long bEnd) {
        return Math.max(0L, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
    }
}
