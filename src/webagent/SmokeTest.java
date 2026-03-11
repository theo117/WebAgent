package webagent;

import webagent.model.AuditReport;
import webagent.model.ManagedSite;
import webagent.service.RemediationService;
import webagent.service.SiteAuditor;

import java.nio.file.Path;
import java.util.List;

public final class SmokeTest {
    private SmokeTest() {
    }

    public static void main(String[] args) {
        SiteAuditor auditor = new SiteAuditor();
        AuditReport invalidUrlReport = auditor.audit("http://[");

        RemediationService remediationService = new RemediationService();
        ManagedSite site = new ManagedSite(
                Path.of("sample-site").toAbsolutePath().normalize(),
                Path.of("sample-site/index.html").toAbsolutePath().normalize(),
                "https://example.com",
                "Example Home",
                "A healthy example site.",
                "en");

        var results = remediationService.apply(site, List.of(
                "title",
                "meta-description",
                "lang",
                "canonical",
                "robots",
                "security-headers"));

        System.out.println("Invalid URL reachable: " + invalidUrlReport.reachable());
        System.out.println("Issue count: " + invalidUrlReport.issues().size());
        System.out.println("Fix results: " + results.size());
        System.out.println("Updated file: " + site.indexFile());
        System.exit(0);
    }
}
