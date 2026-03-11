package webagent.service;

import webagent.model.FixResult;
import webagent.model.ManagedSite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RemediationService {
    public List<FixResult> apply(ManagedSite site, List<String> actions) {
        List<FixResult> results = new ArrayList<>();
        for (String action : actions) {
            results.add(applyOne(site, action));
        }
        return results;
    }

    private FixResult applyOne(ManagedSite site, String action) {
        try {
            return switch (action) {
                case "title" -> updateTitle(site);
                case "meta-description" -> updateMetaDescription(site);
                case "lang" -> updateLang(site);
                case "canonical" -> updateCanonical(site);
                case "robots" -> updateRobots(site);
                case "security-headers" -> writeSecurityHeaders(site);
                default -> new FixResult(action, false, "Unsupported action.");
            };
        } catch (IOException ex) {
            return new FixResult(action, false, ex.getMessage());
        }
    }

    private FixResult updateTitle(ManagedSite site) throws IOException {
        String html = read(site.indexFile());
        if (html.contains("<title>")) {
            html = html.replaceAll("(?is)<title>.*?</title>", "<title>" + escape(site.title()) + "</title>");
        } else if (html.contains("</head>")) {
            html = html.replace("</head>", "    <title>" + escape(site.title()) + "</title>\n</head>");
        } else {
            html = "<head>\n    <title>" + escape(site.title()) + "</title>\n</head>\n" + html;
        }
        write(site.indexFile(), html);
        return new FixResult("title", true, "Updated title.");
    }

    private FixResult updateMetaDescription(ManagedSite site) throws IOException {
        String html = read(site.indexFile());
        String tag = "<meta name=\"description\" content=\"" + escape(site.metaDescription()) + "\" />";
        if (html.matches("(?is).*<meta\\s+name=[\"']description[\"'].*")) {
            html = html.replaceAll("(?is)<meta\\s+name=[\"']description[\"'][^>]*>", tag);
        } else if (html.contains("</head>")) {
            html = html.replace("</head>", "    " + tag + "\n</head>");
        } else {
            html = "<head>\n    " + tag + "\n</head>\n" + html;
        }
        write(site.indexFile(), html);
        return new FixResult("meta-description", true, "Updated meta description.");
    }

    private FixResult updateLang(ManagedSite site) throws IOException {
        String html = read(site.indexFile());
        if (html.matches("(?is).*<html[^>]*\\slang=[\"'][^\"']+[\"'][^>]*>.*")) {
            html = html.replaceAll("(?is)<html([^>]*)\\slang=[\"'][^\"']+[\"']([^>]*)>", "<html$1 lang=\"" + escape(site.lang()) + "\"$2>");
        } else if (html.matches("(?is).*<html[^>]*>.*")) {
            html = html.replaceFirst("(?is)<html([^>]*)>", "<html$1 lang=\"" + escape(site.lang()) + "\">");
        } else {
            html = "<html lang=\"" + escape(site.lang()) + "\">\n" + html + "\n</html>";
        }
        write(site.indexFile(), html);
        return new FixResult("lang", true, "Updated html lang.");
    }

    private FixResult updateCanonical(ManagedSite site) throws IOException {
        String html = read(site.indexFile());
        String tag = "<link rel=\"canonical\" href=\"" + escape(site.baseUrl()) + "\" />";
        if (html.matches("(?is).*<link\\s+rel=[\"']canonical[\"'].*")) {
            html = html.replaceAll("(?is)<link\\s+rel=[\"']canonical[\"'][^>]*>", tag);
        } else if (html.contains("</head>")) {
            html = html.replace("</head>", "    " + tag + "\n</head>");
        } else {
            html = "<head>\n    " + tag + "\n</head>\n" + html;
        }
        write(site.indexFile(), html);
        return new FixResult("canonical", true, "Updated canonical URL.");
    }

    private FixResult updateRobots(ManagedSite site) throws IOException {
        Path robots = site.root().resolve("robots.txt");
        write(robots, "User-agent: *\nAllow: /\nSitemap: " + site.baseUrl() + "/sitemap.xml\n");
        return new FixResult("robots", true, "Wrote robots.txt.");
    }

    private FixResult writeSecurityHeaders(ManagedSite site) throws IOException {
        Path ops = site.root().resolve("ops");
        Files.createDirectories(ops);
        Path headers = ops.resolve("security-headers.conf");
        String content = """
                add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
                add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline' https:; script-src 'self' https:;" always;
                add_header X-Content-Type-Options "nosniff" always;
                add_header Referrer-Policy "strict-origin-when-cross-origin" always;
                """;
        write(headers, content);
        return new FixResult("security-headers", true, "Wrote proxy security header snippet to ops/security-headers.conf.");
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void write(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
