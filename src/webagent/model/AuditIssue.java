package webagent.model;

public record AuditIssue(
        String severity,
        String category,
        String message,
        String suggestion,
        boolean autoFixable,
        String fixKey) {
}
