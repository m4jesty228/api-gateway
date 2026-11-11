import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mesure de latence du gateway (cahier des charges §20 : p95 < 300 ms).
 *
 *   java tools/LoadTest.java                       200 requetes/s pendant 20 s sur GET /api/accounts/42/balance
 *   GW=http://hote:8080 TOKEN=... RPS=100 SECONDS=60 PATH_UNDER_TEST=/api/accounts/42/balance java tools/LoadTest.java
 *
 * Charge ouverte : les requetes partent a cadence fixe (RPS) quelle que soit la vitesse des reponses,
 * ce qui evite de sous-estimer la latence (omission coordonnee). Sans TOKEN, un token est demande au faux Identity.
 * ATTENTION : la limite de debit par IP (20 req/s) renverrait des 429 : lancer le gateway avec RATE_LIMIT_ENABLED=false
 * pour mesurer la latence du routage lui-meme. Seules les reponses 2xx/3xx/4xx hors 429 sont comptees comme reussies.
 * Code de sortie 0 si p95 < SEUIL_MS (300) et aucune erreur.
 */
public class LoadTest {
    public static void main(String[] args) throws Exception {
        String gw = env("GW", "http://localhost:8080");
        String path = env("PATH_UNDER_TEST", "/api/accounts/42/balance");
        int rps = Integer.parseInt(env("RPS", "200"));
        int seconds = Integer.parseInt(env("SECONDS", "20"));
        double threshold = Double.parseDouble(env("SEUIL_MS", "300"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        String token = env("TOKEN", "");
        if (token.isEmpty()) {
            String b = http.send(HttpRequest.newBuilder(URI.create(gw + "/api/auth/login?role=USER"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            int i = b.indexOf("\"accessToken\":\"");
            if (i < 0) throw new IllegalStateException("Aucun token : definir TOKEN ou lancer MockServices");
            token = b.substring(i + 15, b.indexOf('"', i + 15));
        }
        final String bearer = "Bearer " + token;
        HttpRequest request = HttpRequest.newBuilder(URI.create(gw + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", bearer).GET().build();

        // Echauffement : 3 s (JIT, connexions, cache JWKS) non compte dans la mesure
        for (int i = 0; i < rps * 3; i++) { http.sendAsync(request, HttpResponse.BodyHandlers.discarding()); Thread.sleep(1000 / Math.max(1, rps)); }
        Thread.sleep(1000);

        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger(), limited = new AtomicInteger();
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        int total = rps * seconds;
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger sent = new AtomicInteger();
        long periodNanos = 1_000_000_000L / rps;
        long start = System.nanoTime();
        clock.scheduleAtFixedRate(() -> {
            // rattrape le retard eventuel de l'ordonnanceur : on envoie tout ce qui aurait du partir
            long due = Math.min(total, (System.nanoTime() - start) / periodNanos + 1);
            while (sent.get() < due) {
                sent.incrementAndGet();
                long t0 = System.nanoTime();
                http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((r, e) -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (e != null || r.statusCode() >= 500) errors.incrementAndGet();
                    else if (r.statusCode() == 429) limited.incrementAndGet();
                    else latencies.add(ms);
                    done.countDown();
                });
            }
        }, 0, 5, TimeUnit.MILLISECONDS);
        done.await(seconds + 30L, TimeUnit.SECONDS);
        clock.shutdownNow();

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) { System.out.println("Aucune reponse exploitable"); System.exit(2); }
        double p95 = percentile(sorted, 95);
        System.out.printf("requetes=%d reussies=%d erreurs5xx/reseau=%d limitees429=%d debit=%d/s%n",
                total, sorted.length, errors.get(), limited.get(), rps);
        System.out.printf("latence ms : p50=%.0f p90=%.0f p95=%.0f p99=%.0f max=%d%n", percentile(sorted, 50),
                percentile(sorted, 90), p95, percentile(sorted, 99), sorted[sorted.length - 1]);
        boolean pass = p95 < threshold && errors.get() == 0;
        System.out.println((pass ? "[OK]" : "[ECHEC]") + " p95 = " + (long) p95 + " ms (seuil " + (long) threshold + " ms)");
        System.exit(pass ? 0 : 1);
    }

    private static double percentile(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)];
    }

    private static String env(String n, String d) { String v = System.getenv(n); return v == null || v.isBlank() ? d : v; }
}
