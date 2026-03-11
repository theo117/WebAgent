package webagent.model;

import java.util.List;

public record AuditReport(
        String url,
        long checkedAtEpochMs,
        int statusCode,
        long latencyMs,
        boolean reachable,
        String summary,
        List<AuditIssue> issues) {
}
