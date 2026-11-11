import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Recette de bout en bout du gateway (scenario de demonstration, cahier des charges §24, vu du gateway).
 * Une seule commande, un verdict par controle, code de sortie 0 si tout passe :
 *
 *   java tools/E2E.java                                  (contre http://localhost:8080)
 *   GW=https://gateway.exemple USER_TOKEN=... ADMIN_TOKEN=... java tools/E2E.java
 *
 * Variables : GW (adresse), USER_TOKEN / ADMIN_TOKEN (JWT reels ; sinon le faux Identity de MockServices est
 * utilise pour en obtenir), ACCOUNT_ID (defaut 42), RATE=true (ajoute le controle des 10 logins/minute : a lancer
 * en dernier, il bloque l'IP une minute), MOCK=true (ajoute les controles qui exigent les faux services :
 * /slow et /boom pour la resilience). Aucun secret n'est ecrit dans la sortie.
 */
public class E2E {

    private static final String GW = env("GW", "http://localhost:8080");
    private static final String ACCOUNT = env("ACCOUNT_ID", "42");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<String> FAILURES = new ArrayList<>();
    private static int total = 0;

    record Res(int status, String body, java.net.http.HttpHeaders headers, long ms) {
        String header(String name) { return headers.firstValue(name).orElse(""); }
    }

    public static void main(String[] args) throws Exception {
        String user = tokenOrMock("USER_TOKEN", "USER");
        String admin = tokenOrMock("ADMIN_TOKEN", "ADMIN");
        String balance = "/api/accounts/" + ACCOUNT + "/balance";

        section("Sante et routes publiques");
        check("1  GET /actuator/health/liveness = 200", call("GET", "/actuator/health/liveness", null, null).status() == 200);
        Res reg = call("POST", "/api/users/register", "{\"email\":\"e2e@example.com\"}", null);
        check("2  POST /api/users/register sans token : pas de 401 (route publique routee)", reg.status() != 401 && reg.status() != 404 && reg.status() != 429 && reg.status() != 503);

        section("Authentification");
        check("3  token utilisable obtenu", user != null && admin != null);
        check("4  sans token = 401 UNAUTHORIZED", status(call("GET", balance, null, null), 401, "UNAUTHORIZED"));
        check("5  avec token = pas 401/403", ok(call("GET", balance, null, user)));
        String tampered = user.substring(0, user.length() - 2) + (user.endsWith("AA") ? "BB" : "AA");
        check("6  token altere = 401", call("GET", balance, null, tampered).status() == 401);

        section("Autorisations");
        check("7  /debit interne = 403 (meme ADMIN)", call("POST", "/api/accounts/" + ACCOUNT + "/debit", "{}", admin).status() == 403);
        check("8  /credit interne = 403", call("POST", "/api/accounts/" + ACCOUNT + "/credit", "{}", user).status() == 403);
        check("9  /unblock avec USER = 403", call("POST", "/api/accounts/" + ACCOUNT + "/unblock", null, user).status() == 403);
        int adminStatus = call("POST", "/api/accounts/" + ACCOUNT + "/unblock", null, admin).status();
        check("10 /unblock avec ADMIN = pas 401/403 (reponse du service)", adminStatus != 401 && adminStatus != 403);
        check("11 route inconnue authentifiee = 404 NOT_FOUND", status(call("GET", "/api/cards/1", null, user), 404, "NOT_FOUND"));

        section("Validation des entrees");
        check("12 Idempotency-Key valide transmise (pas 400)", call("POST", "/api/payments/transfer", "{\"amount\":1}", user, "Idempotency-Key", "9f1c2b7e-3a44-4d0e-8b19-6f2a5c7d1e30").status() != 400);
        check("13 Idempotency-Key invalide = 400", call("POST", "/api/payments/transfer", "{}", user, "Idempotency-Key", "x").status() == 400);
        check("14 corps de 2 Mio = 413 PAYLOAD_TOO_LARGE", status(call("POST", "/api/payments/transfer", "x".repeat(2 * 1024 * 1024), user), 413, "PAYLOAD_TOO_LARGE"));
        check("15 Content-Type text/plain = 415", status(call("POST", "/api/payments/transfer", "abc", user, "Content-Type", "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE"));

        section("Agregation, tracabilite, CORS, en-tetes");
        Res dash = call("GET", "/api/dashboard/" + ACCOUNT, null, user);
        check("16 /api/dashboard/{id} = 200 avec accountId et degraded", dash.status() == 200 && dash.body().contains("\"accountId\":\"" + ACCOUNT + "\"") && dash.body().contains("\"degraded\""));
        check("17 /api/dashboard/<id invalide> = 400", call("GET", "/api/dashboard/a%2Fb", null, user).status() == 400);
        Res traced = call("GET", balance, null, user, "X-Trace-Id", "e2e-trace-0001");
        check("18 X-Trace-Id valide renvoye tel quel", traced.header("X-Trace-Id").equals("e2e-trace-0001"));
        check("19 X-Trace-Id dangereux remplace par un UUID", !call("GET", balance, null, user, "X-Trace-Id", "bad id;<x>").header("X-Trace-Id").contains("<"));
        Res cors = call("OPTIONS", balance, null, null, "Origin", env("ORIGIN", "http://localhost:3000"), "Access-Control-Request-Method", "GET");
        check("20 CORS : origine autorisee acceptee, jamais *", !cors.header("Access-Control-Allow-Origin").equals("*") && !cors.header("Access-Control-Allow-Origin").isEmpty());
        check("21 CORS : origine inconnue refusee", call("OPTIONS", balance, null, null, "Origin", "https://evil.example", "Access-Control-Request-Method", "GET").header("Access-Control-Allow-Origin").isEmpty());
        check("22 en-tete Cross-Origin-Resource-Policy present", !call("GET", balance, null, null).header("Cross-Origin-Resource-Policy").isEmpty());

        if (Boolean.parseBoolean(env("MOCK", "false"))) {
            section("Resilience (faux services)");
            Res boom = call("GET", "/api/accounts/" + ACCOUNT + "/boom", null, user);
            check("23 service en 503 = 503 SERVICE_UNAVAILABLE au format commun", status(boom, 503, "SERVICE_UNAVAILABLE"));
            Res slow = call("GET", "/api/accounts/" + ACCOUNT + "/slow", null, user);
            check("24 service lent = 503 en ~3 s (mesure " + slow.ms() + " ms)", slow.status() == 503 && slow.ms() < 4500);
            check("25 erreur metier 409 transmise telle quelle", call("GET", "/api/accounts/" + ACCOUNT + "/conflict", null, user).status() == 409);
        }
        if (Boolean.parseBoolean(env("RATE", "false"))) {
            section("Limite de debit (bloque l'IP une minute)");
            int last = 0;
            Res limited = null;
            for (int i = 0; i < 12 && last != 429; i++) {
                limited = call("POST", "/api/auth/login", "{}", null);
                last = limited.status();
            }
            check("26 login repete = 429 avec Retry-After", last == 429 && !limited.header("Retry-After").isEmpty());
        }

        System.out.println("\n" + (total - FAILURES.size()) + "/" + total + " controles reussis");
        FAILURES.forEach(f -> System.out.println("  ECHEC : " + f));
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ---- utilitaires ----

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    private static void section(String title) { System.out.println("\n== " + title); }

    private static void check(String label, boolean passed) {
        total++;
        System.out.println((passed ? "  [OK]    " : "  [ECHEC] ") + label);
        if (!passed) FAILURES.add(label);
    }

    private static boolean ok(Res r) { return r.status() != 401 && r.status() != 403 && r.status() != 429; }

    /** Statut attendu ET code JSON attendu dans le corps. */
    private static boolean status(Res r, int expected, String code) {
        return r.status() == expected && r.body().contains("\"code\":\"" + code + "\"") && r.body().contains("\"traceId\"");
    }

    private static String tokenOrMock(String variable, String role) throws Exception {
        String given = System.getenv(variable);
        if (given != null && !given.isBlank()) return given;
        Res r = call("POST", "/api/auth/login?role=" + role, "{}", null);   // faux Identity (MockServices)
        String b = r.body();
        int i = b.indexOf("\"accessToken\":\"");
        return i < 0 ? null : b.substring(i + 15, b.indexOf('"', i + 15));
    }

    private static Res call(String method, String path, String body, String token, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(GW + path)).timeout(Duration.ofSeconds(15));
            boolean contentType = false;
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
                contentType |= headers[i].equalsIgnoreCase("Content-Type");
            }
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body != null && !contentType) b.header("Content-Type", "application/json");
            b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
            long t0 = System.nanoTime();
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body(), r.headers(), (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            return new Res(-1, String.valueOf(e), java.net.http.HttpHeaders.of(java.util.Map.of(), (a, c) -> true), 0);
        }
    }
}
