package webagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import webagent.model.AuditReport;
import webagent.model.ManagedSite;
import webagent.service.MonitorService;
import webagent.service.RemediationService;
import webagent.service.SiteAuditor;
import webagent.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public final class AppServer {
    private final HttpServer server;
    private final SiteAuditor auditor = new SiteAuditor();
    private final RemediationService remediationService = new RemediationService();
    private final MonitorService monitorService = new MonitorService(auditor);
    private final String adminToken = System.getenv("WEBAGENT_ADMIN_TOKEN");
    private final Set<String> allowedHosts = parseAllowedHosts(System.getenv("WEBAGENT_ALLOWED_HOSTS"));
    private final int rateLimitPerMinute = parseInt(System.getenv("WEBAGENT_RATE_LIMIT_PER_MINUTE"), 30);
    private final Map<String, RateWindow> rateWindows = new HashMap<>();

    public AppServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/audit", this::handleAudit);
        server.createContext("/api/monitor/start", this::handleStartMonitor);
        server.createContext("/api/monitor/latest", this::handleLatestMonitor);
        server.createContext("/api/fix", this::handleFix);
    }

    public void start() {
        server.start();
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        sendHtml(exchange, 200, pageHtml());
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleAudit(HttpExchange exchange) throws IOException {
        if (!requireAdmin(exchange)) {
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String url = params.getOrDefault("url", "");
        if (!isAllowedTarget(url)) {
            sendJson(exchange, 403, "{\"message\":\"Target host is not allowed.\"}");
            return;
        }
        AuditReport report = auditor.audit(url);
        sendJson(exchange, 200, Json.auditReport(report));
    }

    private void handleStartMonitor(HttpExchange exchange) throws IOException {
        if (!requireAdmin(exchange)) {
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String url = params.getOrDefault("url", "");
        if (!isAllowedTarget(url)) {
            sendJson(exchange, 403, "{\"message\":\"Target host is not allowed.\"}");
            return;
        }
        int interval = parseInt(params.get("intervalSeconds"), 60);
        int effectiveInterval = Math.max(15, interval);
        monitorService.start(url, effectiveInterval);
        sendJson(exchange, 200, "{\"started\":true,\"url\":\"" + escape(url) + "\",\"intervalSeconds\":" + effectiveInterval + "}");
    }

    private void handleLatestMonitor(HttpExchange exchange) throws IOException {
        if (!requireAdmin(exchange)) {
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String url = params.getOrDefault("url", "");
        if (!isAllowedTarget(url)) {
            sendJson(exchange, 403, "{\"message\":\"Target host is not allowed.\"}");
            return;
        }
        AuditReport latest = monitorService.latest(url);
        if (latest == null) {
            sendJson(exchange, 404, "{\"message\":\"No report available yet.\"}");
            return;
        }
        sendJson(exchange, 200, Json.auditReport(latest));
    }

    private void handleFix(HttpExchange exchange) throws IOException {
        if (!requireAdmin(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"POST required.\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = formParams(body);
        Path root = Path.of(params.getOrDefault("siteRoot", ".")).toAbsolutePath().normalize();
        Path indexFile = root.resolve(params.getOrDefault("indexFile", "index.html")).normalize();
        if (!indexFile.startsWith(root)) {
            sendJson(exchange, 400, "{\"message\":\"indexFile must stay inside siteRoot.\"}");
            return;
        }

        ManagedSite site = new ManagedSite(
                root,
                indexFile,
                params.getOrDefault("baseUrl", "http://localhost"),
                params.getOrDefault("title", "Healthy Website"),
                params.getOrDefault("metaDescription", "A monitored website kept healthy by WebAgent."),
                params.getOrDefault("lang", "en"));

        List<String> actions = splitActions(params.getOrDefault("actions", ""));
        if (actions.isEmpty()) {
            actions = List.of("title", "meta-description", "lang", "canonical", "robots", "security-headers");
        }

        sendJson(exchange, 200, Json.fixResults(remediationService.apply(site, actions)));
    }

    private Map<String, String> queryParams(URI uri) {
        return formParams(uri.getRawQuery() == null ? "" : uri.getRawQuery());
    }

    private Map<String, String> formParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            params.put(key, value);
        }
        return params;
    }

    private List<String> splitActions(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body);
    }

    private void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        send(exchange, status, body);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private boolean requireAdmin(HttpExchange exchange) throws IOException {
        if (adminToken == null || adminToken.isBlank()) {
            sendJson(exchange, 503, "{\"message\":\"WEBAGENT_ADMIN_TOKEN is not configured.\"}");
            return false;
        }
        if (!allowRequest(exchange)) {
            sendJson(exchange, 429, "{\"message\":\"Rate limit exceeded.\"}");
            return false;
        }
        String supplied = bearerToken(exchange);
        if (!adminToken.equals(supplied)) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            sendJson(exchange, 401, "{\"message\":\"Unauthorized.\"}");
            return false;
        }
        return true;
    }

    private String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return "";
        }
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return "";
    }

    private synchronized boolean allowRequest(HttpExchange exchange) {
        String client = clientAddress(exchange);
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.get(client);
        if (window == null || now >= window.resetAtEpochMs) {
            rateWindows.put(client, new RateWindow(1, now + 60_000));
            return true;
        }
        if (window.count >= rateLimitPerMinute) {
            return false;
        }
        window.count++;
        return true;
    }

    private String clientAddress(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private boolean isAllowedTarget(String rawUrl) {
        if (allowedHosts.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (allowedHosts.contains(normalizedHost)) {
                return true;
            }
            for (String allowedHost : allowedHosts) {
                if (normalizedHost.endsWith("." + allowedHost)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private Set<String> parseAllowedHosts(String raw) {
        Set<String> hosts = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return hosts;
        }
        for (String part : raw.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (!host.isBlank()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static final class RateWindow {
        private int count;
        private final long resetAtEpochMs;

        private RateWindow(int count, long resetAtEpochMs) {
            this.count = count;
            this.resetAtEpochMs = resetAtEpochMs;
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String pageHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>WebAgent Dashboard</title>
                    <style>
                        :root {
                            --bg: #f6f1e6;
                            --panel: rgba(255, 252, 245, 0.92);
                            --ink: #1e2a3a;
                            --accent: #8f6a1c;
                            --accent-2: #315b8a;
                            --line: #d9c9a3;
                            --glow: rgba(255, 215, 140, 0.32);
                        }
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            font-family: "Palatino Linotype", Georgia, serif;
                            color: var(--ink);
                            background:
                                radial-gradient(circle at top center, var(--glow), transparent 24%),
                                linear-gradient(180deg, rgba(49,91,138,.12), transparent 28%),
                                linear-gradient(135deg, #faf6ee, #f2e9d8 52%, #f9f5ec);
                        }
                        .shell {
                            max-width: 1100px;
                            margin: 0 auto;
                            padding: 32px 20px 60px;
                        }
                        h1 {
                            font-size: clamp(2.2rem, 5vw, 4.5rem);
                            line-height: 0.95;
                            margin: 0 0 12px;
                            letter-spacing: -0.04em;
                            color: #24456b;
                            text-shadow: 0 1px 0 rgba(255,255,255,.7);
                        }
                        p.lead {
                            max-width: 780px;
                            font-size: 1.05rem;
                            margin-bottom: 28px;
                        }
                        .grid {
                            display: grid;
                            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                            gap: 18px;
                        }
                        .panel {
                            background: var(--panel);
                            border: 1px solid var(--line);
                            border-radius: 20px;
                            padding: 20px;
                            box-shadow: 0 18px 45px rgba(36, 54, 79, 0.12);
                            position: relative;
                            overflow: hidden;
                        }
                        .panel::before {
                            content: "";
                            position: absolute;
                            inset: 0 auto auto 0;
                            width: 100%;
                            height: 4px;
                            background: linear-gradient(90deg, #315b8a, #d0a94f, #315b8a);
                        }
                        label {
                            display: block;
                            font-size: 0.92rem;
                            margin: 14px 0 6px;
                            color: #4b3d21;
                        }
                        input, textarea {
                            width: 100%;
                            border: 1px solid #ccb98d;
                            border-radius: 12px;
                            padding: 10px 12px;
                            background: #fffefb;
                            font: inherit;
                        }
                        button {
                            margin-top: 16px;
                            border: 0;
                            border-radius: 999px;
                            background: linear-gradient(135deg, #24456b, #315b8a);
                            color: white;
                            padding: 11px 18px;
                            font: inherit;
                            cursor: pointer;
                        }
                        button.alt { background: linear-gradient(135deg, #8f6a1c, #c49a3d); }
                        .result {
                            margin-top: 18px;
                            white-space: pre-wrap;
                            font-family: Consolas, monospace;
                            font-size: 0.9rem;
                            background: rgba(255,255,255,0.88);
                            border-radius: 14px;
                            padding: 14px;
                            border: 1px solid var(--line);
                            min-height: 180px;
                        }
                    </style>
                </head>
                <body>
                    <div class="shell">
                        <h1>Watch your site.<br/>Repair what is safe.</h1>
                        <p class="lead">WebAgent audits a URL for health issues, explains what needs attention, and can apply safe fixes to a local static site you control.</p>
                        <div class="grid">
                            <section class="panel">
                                <h2>Audit</h2>
                                <label for="adminToken">Admin token</label>
                                <input id="adminToken" type="password" placeholder="Set WEBAGENT_ADMIN_TOKEN in Render first" />
                                <label for="auditUrl">URL</label>
                                <input id="auditUrl" value="http://localhost:8080/" />
                                <label for="intervalSeconds">Monitor interval in seconds</label>
                                <input id="intervalSeconds" value="60" />
                                <button onclick="runAudit()">Run audit</button>
                                <button class="alt" onclick="startMonitor()">Start monitor</button>
                                <button class="alt" onclick="latestMonitor()">Latest monitor result</button>
                                <div id="auditResult" class="result"></div>
                            </section>
                            <section class="panel">
                                <h2>Apply fixes</h2>
                                <label for="siteRoot">Site root</label>
                                <input id="siteRoot" placeholder="C:\\sites\\my-site" />
                                <label for="indexFile">Index file relative to site root</label>
                                <input id="indexFile" value="index.html" />
                                <label for="baseUrl">Base URL</label>
                                <input id="baseUrl" placeholder="https://example.com" />
                                <label for="title">Title</label>
                                <input id="title" value="Healthy Website" />
                                <label for="metaDescription">Meta description</label>
                                <textarea id="metaDescription">A monitored website kept healthy by WebAgent.</textarea>
                                <label for="lang">Language</label>
                                <input id="lang" value="en" />
                                <label for="actions">Actions</label>
                                <input id="actions" value="title,meta-description,lang,canonical,robots,security-headers" />
                                <button onclick="applyFixes()">Apply fixes</button>
                                <div id="fixResult" class="result"></div>
                            </section>
                        </div>
                    </div>
                    <script>
                        function pretty(data) {
                            return JSON.stringify(data, null, 2);
                        }

                        function authHeaders(extra = {}) {
                            const token = document.getElementById('adminToken').value;
                            return {
                                ...extra,
                                Authorization: 'Bearer ' + token
                            };
                        }

                        async function runAudit() {
                            const url = document.getElementById('auditUrl').value;
                            const response = await fetch('/api/audit?url=' + encodeURIComponent(url), {
                                headers: authHeaders()
                            });
                            const data = await response.json();
                            document.getElementById('auditResult').textContent = pretty(data);
                        }

                        async function startMonitor() {
                            const url = document.getElementById('auditUrl').value;
                            const intervalSeconds = document.getElementById('intervalSeconds').value;
                            const response = await fetch('/api/monitor/start?url=' + encodeURIComponent(url) + '&intervalSeconds=' + encodeURIComponent(intervalSeconds), {
                                headers: authHeaders()
                            });
                            const data = await response.json();
                            document.getElementById('auditResult').textContent = pretty(data);
                        }

                        async function latestMonitor() {
                            const url = document.getElementById('auditUrl').value;
                            const response = await fetch('/api/monitor/latest?url=' + encodeURIComponent(url), {
                                headers: authHeaders()
                            });
                            const data = await response.json();
                            document.getElementById('auditResult').textContent = pretty(data);
                        }

                        async function applyFixes() {
                            const body = new URLSearchParams({
                                siteRoot: document.getElementById('siteRoot').value,
                                indexFile: document.getElementById('indexFile').value,
                                baseUrl: document.getElementById('baseUrl').value,
                                title: document.getElementById('title').value,
                                metaDescription: document.getElementById('metaDescription').value,
                                lang: document.getElementById('lang').value,
                                actions: document.getElementById('actions').value
                            });
                            const response = await fetch('/api/fix', {
                                method: 'POST',
                                headers: authHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
                                body
                            });
                            const data = await response.json();
                            document.getElementById('fixResult').textContent = pretty(data);
                        }
                    </script>
                </body>
                </html>
                """;
    }
}
