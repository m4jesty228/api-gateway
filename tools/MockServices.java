import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Faux services pour tester le gateway sans attendre les autres membres du groupe.
 * Lancement (depuis le dossier du projet) :  java tools/MockServices.java
 *
 * - Tous les services renvoient en JSON ce qu'ils ont recu (methode, chemin, en-tetes).
 * - Le faux Identity joue en plus le role d'Identity Service du contrat (section 4) :
 *     POST /auth/login?role=ADMIN&ttl=900  -> emet un vrai JWT RS256 (role et duree reglables)
 *     GET  /.well-known/jwks.json          -> publie la cle publique pour verifier ces tokens
 *   ttl negatif (ex. -300) = token deja expire, pratique pour tester le rejet.
 */
public class MockServices {

    // Meme table que le contrat d'interface (section 2) : nom du service -> port
    private static final Map<String, Integer> SERVICES = new TreeMap<>(Map.of(
            "identity-service", 8081,
            "core-banking-service", 8082,
            "payment-service", 8083,
            "notification-service", 8084));

    // Paire de cles RSA generee a chaque demarrage ; le kid change aussi, ce qui force le gateway
    // a recharger la cle publique si on relance ce programme sans redemarrer le gateway
    private static KeyPair keys;
    private static String kid;

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        kid = UUID.randomUUID().toString();

        for (Map.Entry<String, Integer> s : SERVICES.entrySet()) {
            HttpServer server = HttpServer.create(new InetSocketAddress(s.getValue()), 0);
            // Plusieurs threads : une requete lente (/slow) ne doit pas bloquer les autres
            server.setExecutor(Executors.newCachedThreadPool());
            // Contexte "/" : tout chemin non specifique est renvoye en echo
            server.createContext("/", exchange -> echo(exchange, s.getKey()));
            if (s.getKey().equals("identity-service")) {
                // Les contextes plus precis l'emportent sur "/"
                server.createContext("/.well-known/jwks.json", MockServices::jwks);
                server.createContext("/auth/login", MockServices::login);
            }
            server.start();
            System.out.println("Faux service demarre : " + s.getKey() + " sur le port " + s.getValue());
        }
        System.out.println("Ctrl + C pour arreter.");
    }

    // ---------------------------------------------------------------- Identity (JWT)

    /** Emet un JWT RS256 : claims sub, role, iss, iat, exp conformes au contrat (section 4). */
    private static void login(HttpExchange ex) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String role = query.getOrDefault("role", "USER");
        long ttl = Long.parseLong(query.getOrDefault("ttl", "900"));   // secondes, 15 min par defaut
        long now = Instant.now().getEpochSecond();

        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String payload = "{\"sub\":\"0b2f6a4e-1c1d-4f3e-9d57-8a3c1f2e9b10\","
                + "\"role\":\"" + esc(role) + "\","
                + "\"iss\":\"identity-service\","
                + "\"iat\":" + now + ",\"exp\":" + (now + ttl) + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payload.getBytes(StandardCharsets.UTF_8));
        String token = signingInput + "." + b64(sign(signingInput));

        respond(ex, "{\"accessToken\":\"" + token + "\",\"refreshToken\":\"mock-refresh\"}");
    }

    /** Publie la cle publique au format JWKS (RFC 7517) : modulus n et exposant e en base64url. */
    private static void jwks(HttpExchange ex) throws IOException {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        respond(ex, "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\","
                + "\"n\":\"" + b64(unsigned(pub.getModulus())) + "\","
                + "\"e\":\"" + b64(unsigned(pub.getPublicExponent())) + "\"}]}");
    }

    /** Signature RS256 = RSA + SHA-256 sur "entete.contenu". */
    private static byte[] sign(String input) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** BigInteger -> octets sans l'eventuel octet de signe 0x00 en tete (exige par le format JWKS). */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            result.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return result;
    }

    // ---------------------------------------------------------------- Echo commun

    /** Renvoie en JSON le nom du service, la methode, le chemin recu et les en-tetes. */
    private static void echo(HttpExchange ex, String service) throws IOException {
        // Chemins speciaux pour tester la resilience du gateway (B7)
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/slow")) {          // service qui ne repond pas a temps
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (path.endsWith("/boom")) {   // service qui repond 503
            respond(ex, 503, "{\"code\":\"BOOM\",\"message\":\"panne simulee\"}");
            return;
        } else if (path.endsWith("/conflict")) {   // erreur metier : doit traverser le gateway sans etre touchee
            respond(ex, 409, "{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"Solde insuffisant\",\"traceId\":\"mock\"}");
            return;
        }
        // En-tetes tries pour une sortie stable et lisible
        String headers = new TreeMap<>(ex.getRequestHeaders()).entrySet().stream()
                .map(h -> "\"" + esc(h.getKey()) + "\":\"" + esc(String.join(",", h.getValue())) + "\"")
                .collect(Collectors.joining(","));
        respond(ex, "{\"service\":\"" + service + "\","
                + "\"method\":\"" + esc(ex.getRequestMethod()) + "\","
                + "\"path\":\"" + esc(ex.getRequestURI().toString()) + "\","
                + "\"headers\":{" + headers + "}}");
    }

    private static void respond(HttpExchange ex, String json) throws IOException {
        respond(ex, 200, json);
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Echappe les guillemets et antislashs pour garder un JSON valide. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
