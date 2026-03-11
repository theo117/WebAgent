package webagent.service;

import webagent.model.AuditReport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MonitorService {
    private final SiteAuditor auditor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, AuditReport> latestReports = new ConcurrentHashMap<>();

    public MonitorService(SiteAuditor auditor) {
        this.auditor = auditor;
    }

    public void start(String url, int intervalSeconds) {
        stop(url);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            AuditReport report = auditor.audit(url);
            latestReports.put(url, report);
        }, 0, intervalSeconds, TimeUnit.SECONDS);
        jobs.put(url, future);
    }

    public void stop(String url) {
        ScheduledFuture<?> existing = jobs.remove(url);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    public AuditReport latest(String url) {
        return latestReports.get(url);
    }
}
