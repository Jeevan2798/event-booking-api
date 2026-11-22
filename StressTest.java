// Stress test: N customers try to book the same event at the same instant.
// Run (Java 17+, no build needed):
//   java StressTest.java https://your-app.onrender.com naive 50 100 200 400
//   java StressTest.java https://your-app.onrender.com optimized 200 500 1000 2000
// A request with no response within 10 s (-Dtimeout=ms) counts as DROPPED.
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

public class StressTest {
    static String BASE;
    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    static final long TIMEOUT = Long.getLong("timeout", 10000);
    static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    public static void main(String[] a) throws Exception {
        BASE = (a.length > 0 ? a[0] : "http://localhost:8080").replaceAll("/$", "");
        String mode = a.length > 1 ? a[1] : "optimized";
        List<Integer> levels = new ArrayList<>();
        for (int i = 2; i < a.length; i++) levels.add(Integer.parseInt(a[i]));
        if (levels.isEmpty()) levels = List.of(50, 100, 200, 500);
        int users = Integer.getInteger("users", 100);
        System.out.printf("Target %s | mode=%s | timeout=%dms | %d customer accounts%n", BASE, mode, TIMEOUT, users);

        String org = token(call("POST", "/auth/register", "{\"name\":\"Load Org\",\"email\":\"org-" + RUN
                + "@loadtest.dev\",\"password\":\"pass1234\",\"role\":\"organizer\"}", null).join());
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < users; i += 20) {
            List<CompletableFuture<HttpResponse<String>>> batch = new ArrayList<>();
            for (int j = i; j < Math.min(users, i + 20); j++)
                batch.add(call("POST", "/auth/register", "{\"name\":\"User " + j + "\",\"email\":\"u" + j + "-" + RUN
                        + "@loadtest.dev\",\"password\":\"pass1234\",\"role\":\"customer\"}", null));
            for (var f : batch) tokens.add(token(f.join()));
        }
        for (int n : levels) { awaitQueueDrained(); level(n, mode, org, tokens); }
    }

    static void level(int n, String mode, String org, List<String> tokens) throws Exception {
        int capacity = (int) (n * 0.8); // fewer tickets than buyers -> also tests overselling
        var ev = call("POST", "/events", "{\"title\":\"Stress " + mode + " x" + n
                + "\",\"startsAt\":\"2030-01-01T18:00:00Z\",\"venue\":\"Load Arena\",\"capacity\":" + capacity + "}", org).join();
        String id = find(ev.body(), "\"id\":(\\d+)");
        String path = "/events/" + id + (mode.equals("naive") ? "/book-naive" : "/book");

        AtomicInteger ok = new AtomicInteger(), soldOut = new AtomicInteger(), dropped = new AtomicInteger(), errors = new AtomicInteger();
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        long t0 = System.currentTimeMillis();
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long s = System.currentTimeMillis();
            all.add(call("POST", path, "{\"quantity\":1}", tokens.get(i % tokens.size())).handle((r, ex) -> {
                if (ex != null) { dropped.incrementAndGet(); return null; }
                lat.add(System.currentTimeMillis() - s);
                if (r.statusCode() == 201) ok.incrementAndGet();
                else if (r.statusCode() == 409) soldOut.incrementAndGet();
                else errors.incrementAndGet();
                return null;
            }));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
        long wall = System.currentTimeMillis() - t0;
        Collections.sort(lat);
        Thread.sleep(mode.equals("naive") ? 3000 : 300);
        String audit = call("GET", "/events/" + id + "/bookings", null, org).join().body();
        String sold = find(audit, "\"ticketsSold\":(\\d+)");
        boolean oversold = audit.contains("\"oversold\":true");
        System.out.printf("%5d users | ok %5d | sold-out %5d | DROPPED %5d | errors %4d | success %5.1f%% | p50 %dms p95 %dms | wall %dms | cap %d sold %s%s%n",
                n, ok.get(), soldOut.get(), dropped.get(), errors.get(), (ok.get() + soldOut.get()) * 100.0 / n,
                pct(lat, 0.5), pct(lat, 0.95), wall, capacity, sold, oversold ? "  <-- OVERSOLD!" : "");
    }

    /** Simulated emails from the previous level must finish, or they skew the next level's numbers. */
    static void awaitQueueDrained() throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String h = call("GET", "/health", null, null).join().body();
                if (find(h, "\"queued\":(\\d+)").equals("0") && find(h, "\"running\":(\\d+)").equals("0")) return;
                System.out.println("  ...waiting for background email queue to drain: " + h);
            } catch (Exception e) { /* retry */ }
            Thread.sleep(3000);
        }
    }

    static CompletableFuture<HttpResponse<String>> call(String method, String path, String body, String token) {
        var b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofMillis(TIMEOUT))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String token(HttpResponse<String> r) { return find(r.body(), "\"token\":\"([^\"]+)\""); }

    static String find(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        if (!m.find()) throw new RuntimeException("Unexpected response: " + s);
        return m.group(1);
    }

    static long pct(List<Long> l, double p) { return l.isEmpty() ? 0 : l.get(Math.min(l.size() - 1, (int) (l.size() * p))); }
}
