package webagent.model;

import java.nio.file.Path;

public record ManagedSite(
        Path root,
        Path indexFile,
        String baseUrl,
        String title,
        String metaDescription,
        String lang) {
}
