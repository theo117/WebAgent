package webagent.util;

import webagent.model.AuditIssue;
import webagent.model.AuditReport;
import webagent.model.FixResult;

import java.util.List;
import java.util.stream.Collectors;

public final class Json {
    private Json() {
    }

    public static String auditReport(AuditReport report) {
        return "{"
                + "\"url\":\"" + escape(report.url()) + "\","
                + "\"checkedAtEpochMs\":" + report.checkedAtEpochMs() + ","
                + "\"statusCode\":" + report.statusCode() + ","
                + "\"latencyMs\":" + report.latencyMs() + ","
                + "\"reachable\":" + report.reachable() + ","
                + "\"summary\":\"" + escape(report.summary()) + "\","
                + "\"issues\":[" + report.issues().stream().map(Json::auditIssue).collect(Collectors.joining(",")) + "]"
                + "}";
    }

    public static String fixResults(List<FixResult> results) {
        return "{\"results\":[" + results.stream().map(Json::fixResult).collect(Collectors.joining(",")) + "]}";
    }

    private static String auditIssue(AuditIssue issue) {
        return "{"
                + "\"severity\":\"" + escape(issue.severity()) + "\","
                + "\"category\":\"" + escape(issue.category()) + "\","
                + "\"message\":\"" + escape(issue.message()) + "\","
                + "\"suggestion\":\"" + escape(issue.suggestion()) + "\","
                + "\"autoFixable\":" + issue.autoFixable() + ","
                + "\"fixKey\":\"" + escape(issue.fixKey()) + "\""
                + "}";
    }

    private static String fixResult(FixResult result) {
        return "{"
                + "\"action\":\"" + escape(result.action()) + "\","
                + "\"success\":" + result.success() + ","
                + "\"details\":\"" + escape(result.details()) + "\""
                + "}";
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
