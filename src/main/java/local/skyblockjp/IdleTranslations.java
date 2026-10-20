package local.skyblockjp;

import java.util.*;
import java.util.function.Predicate;

/** Client-thread scheduler: recent items first, one paragraph per interval, bounded session history. */
final class IdleTranslations {
    record Job(String itemKey, String text, String context) {}
    private final LinkedHashMap<String, List<Job>> recent = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private String hovered = "";
    private long lastUse, nextRequest;
    void clear(long now) { recent.clear(); attempted.clear(); hovered = ""; lastUse = now; nextRequest = now; }
    void used(long now) { lastUse = now; }
    void observe(String key, List<Job> jobs, long now) {
        if (key.equals(hovered)) return;
        hovered = key; used(now);
        recent.remove(key); recent.put(key, List.copyOf(jobs.stream().limit(32).toList()));
        while (recent.size() > 20) recent.remove(recent.keySet().iterator().next());
        if (attempted.size() > 1000) attempted.retainAll(recent.values().stream().flatMap(List::stream).map(Job::text).toList());
    }
    Job next(long now, long foregroundUse, Config c, boolean ready, Predicate<String> cached) {
        if (!c.backgroundTranslation || !ready || now < nextRequest
            || now - Math.max(lastUse, foregroundUse) < c.backgroundIdleSeconds * 1000L) return null;
        var items = new ArrayList<>(recent.values()); Collections.reverse(items);
        for (var jobs : items) for (var job : jobs) {
            if (cached.test(job.text()) || attempted.contains(job.text())) continue;
            attempted.add(job.text()); nextRequest = now + c.backgroundIntervalSeconds * 1000L;
            return job;
        }
        return null;
    }
    int itemCount() { return recent.size(); }
}
