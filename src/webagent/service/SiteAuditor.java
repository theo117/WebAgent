package webagent.service;

import webagent.model.AuditIssue;
import webagent.model.AuditReport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SiteAuditor {
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile(
            "<meta\\s+name=[\"']description[\"']\\s+content=[\"'](.*?)[\"']\\s*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LANG_PATTERN = Pattern.compile("<html[^>]*\\slang=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CANONICAL_PATTERN = Pattern.compile(
            "<link\\s+rel=[\"']canonical[\"']\\s+href=[\"'](.*?)[\"']\\s*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HREF_PATTERN = Pattern.compile("href=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AuditReport audit(String rawUrl) {
        long checkedAt = System.currentTimeMillis();
        List<AuditIssue> issues = new ArrayList<>();
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            issues.add(new AuditIssue("error", "url", "The URL is not valid.", "Use a full URL such as https://example.com", false, ""));
            return new AuditReport(rawUrl, checkedAt, 0, 0, false, "Invalid URL.", issues);
        }

        HttpResponse<String> response;
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "WebAgent/1.0")
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException ex) {
            issues.add(new AuditIssue("error", "availability",
                    "The site could not be reached: " + ex.getMessage(),
                    "Check DNS, TLS, firewall rules, and upstream availability.",
                    false,
                    ""));
            return new AuditReport(rawUrl, checkedAt, 0, elapsedMs(start), false, "Site unreachable.", issues);
        }

        long latencyMs = elapsedMs(start);
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        HttpHeaders headers = response.headers();

        if (status >= 400) {
            issues.add(new AuditIssue("error", "availability",
                    "The site returned HTTP " + status + ".",
                    "Fix the failing route or upstream service before anything else.",
                    false,
                    ""));
        }
        if (latencyMs > 1500) {
            issues.add(new AuditIssue("warn", "performance",
                    "Response latency is high at " + latencyMs + " ms.",
                    "Review backend timing, caching, image size, and third-party scripts.",
                    false,
                    ""));
        }

        checkHeader(headers, "strict-transport-security", issues,
                "Missing HSTS header.", "Serve HTTPS everywhere and add Strict-Transport-Security.", true, "security-headers");
        checkHeader(headers, "content-security-policy", issues,
                "Missing Content-Security-Policy header.", "Add a CSP to reduce XSS and unsafe asset loading.", true, "security-headers");
        checkHeader(headers, "x-content-type-options", issues,
                "Missing X-Content-Type-Options header.", "Add X-Content-Type-Options: nosniff.", true, "security-headers");
        checkHeader(headers, "referrer-policy", issues,
                "Missing Referrer-Policy header.", "Add a restrictive referrer policy.", true, "security-headers");

        if (isHtml(headers, body)) {
            checkTitle(body, issues);
            checkMetaDescription(body, issues);
            checkLang(body, issues);
            checkCanonical(body, rawUrl, issues);
            checkInternalLinks(uri, body, issues);
        }

        String summary = summarize(status, latencyMs, issues);
        return new AuditReport(rawUrl, checkedAt, status, latencyMs, true, summary, issues);
    }

    private void checkTitle(String body, List<AuditIssue> issues) {
        Matcher matcher = TITLE_PATTERN.matcher(body);
        if (!matcher.find() || matcher.group(1).trim().isEmpty()) {
            issues.add(new AuditIssue("warn", "seo", "Missing or empty page title.",
                    "Add a descriptive <title> between 30 and 60 characters.", true, "title"));
        }
    }

    private void checkMetaDescription(String body, List<AuditIssue> issues) {
        Matcher matcher = META_DESCRIPTION_PATTERN.matcher(body);
        if (!matcher.find() || matcher.group(1).trim().isEmpty()) {
            issues.add(new AuditIssue("warn", "seo", "Missing meta description.",
                    "Add a concise meta description for search previews.", true, "meta-description"));
        }
    }

    private void checkLang(String body, List<AuditIssue> issues) {
        Matcher matcher = LANG_PATTERN.matcher(body);
        if (!matcher.find() || matcher.group(1).trim().isEmpty()) {
            issues.add(new AuditIssue("warn", "accessibility", "Missing html lang attribute.",
                    "Set <html lang=\"en\"> or the correct primary language.", true, "lang"));
        }
    }

    private void checkCanonical(String body, String rawUrl, List<AuditIssue> issues) {
        Matcher matcher = CANONICAL_PATTERN.matcher(body);
        if (!matcher.find() || matcher.group(1).trim().isEmpty()) {
            issues.add(new AuditIssue("warn", "seo", "Missing canonical link.",
                    "Add a canonical URL to reduce duplicate indexing issues.", true, "canonical"));
        } else if (!matcher.group(1).trim().equals(rawUrl)) {
            issues.add(new AuditIssue("info", "seo", "Canonical URL differs from the audited URL.",
                    "Confirm the canonical target is intentional.", true, "canonical"));
        }
    }

    private void checkInternalLinks(URI baseUri, String body, List<AuditIssue> issues) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher matcher = HREF_PATTERN.matcher(body);
        while (matcher.find() && targets.size() < 12) {
            String href = matcher.group(1).trim();
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:")) {
                continue;
            }
            try {
                URI resolved = baseUri.resolve(href);
                if (sameHost(baseUri, resolved)) {
                    targets.add(resolved.toString());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        int brokenLinks = 0;
        for (String target : targets) {
            if (isBroken(target)) {
                brokenLinks++;
            }
        }
        if (brokenLinks > 0) {
            issues.add(new AuditIssue("warn", "links",
                    brokenLinks + " internal links appear broken.",
                    "Repair or redirect broken internal routes.", false, ""));
        }
    }

    private boolean isBroken(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "WebAgent/1.0")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 400;
        } catch (Exception ex) {
            return true;
        }
    }

    private boolean sameHost(URI left, URI right) {
        return left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private void checkHeader(HttpHeaders headers, String name, List<AuditIssue> issues,
                             String message, String suggestion, boolean autoFixable, String fixKey) {
        if (headers.firstValue(name).isEmpty()) {
            issues.add(new AuditIssue("warn", "security", message, suggestion, autoFixable, fixKey));
        }
    }

    private boolean isHtml(HttpHeaders headers, String body) {
        String contentType = headers.firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        return contentType.contains("text/html") || body.toLowerCase(Locale.ROOT).contains("<html");
    }

    private String summarize(int status, long latencyMs, List<AuditIssue> issues) {
        long errors = issues.stream().filter(issue -> "error".equals(issue.severity())).count();
        long warnings = issues.stream().filter(issue -> "warn".equals(issue.severity())).count();
        if (errors > 0) {
            return "Critical issues found. HTTP " + status + ", " + warnings + " warnings, " + latencyMs + " ms latency.";
        }
        if (warnings > 0) {
            return "Site is reachable but needs attention. HTTP " + status + ", " + warnings + " warnings, " + latencyMs + " ms latency.";
        }
        return "Site looks healthy. HTTP " + status + ", " + latencyMs + " ms latency.";
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
