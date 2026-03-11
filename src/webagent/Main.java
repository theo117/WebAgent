package webagent;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = parsePort(System.getenv("PORT"), 8080);
        AppServer server = new AppServer(port);
        server.start();
        System.out.println("WebAgent running on port " + port);
    }

    private static int parsePort(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
