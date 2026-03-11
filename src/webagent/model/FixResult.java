package webagent.model;

public record FixResult(
        String action,
        boolean success,
        String details) {
}
