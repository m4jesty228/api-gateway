package com.neobank.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Point d'entrée du gateway. Les routes (B2), la sécurité JWT (B3) et le rate limiting (B5)
 * seront ajoutés dans des classes séparées pour garder ce fichier minimal.
 */
@SpringBootApplication
@ConfigurationPropertiesScan // détecte automatiquement GatewayProperties (adresses des services aval)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
