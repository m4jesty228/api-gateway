package com.neobank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/** Vérifie que la table de routage correspond au contrat d'interface (§2). */
@SpringBootTest
class RoutesConfigTests {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void lesQuatreRoutesDuContratSontPresentes() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).extracting(Route::getId)
                .containsExactlyInAnyOrder("identity", "banking", "payment", "notification");
    }

    @Test
    void chaqueRoutePointeVersLeBonService() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        // Adresses par défaut de application.yml (hors variables d'environnement)
        assertThat(routes).extracting(Route::getUri).containsExactlyInAnyOrder(
                URI.create("http://localhost:8081"),
                URI.create("http://localhost:8082"),
                URI.create("http://localhost:8083"),
                URI.create("http://localhost:8084"));
    }

    /** B15 : chaque route rejoue les lectures (GET) en cas de 502/503/504, mais jamais une écriture (risque de doubler un virement). */
    @Test
    void chaqueRouteRetenteSeulementLesGet() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        for (Route route : routes) {
            String retry = route.getFilters().stream().map(Object::toString)
                    .filter(f -> f.contains("Retry")).findFirst().orElse("");
            assertThat(retry).as("filtre Retry de la route " + route.getId()).contains("GET");
            assertThat(retry).as("aucune écriture rejouée sur " + route.getId())
                    .doesNotContain("POST").doesNotContain("PUT").doesNotContain("PATCH").doesNotContain("DELETE");
        }
    }
}
