package com.neobank.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Affiche au démarrage les adresses réellement utilisées par le gateway.
 * Utile à l'intégration : une variable d'environnement mal posée dans la stack Swarm
 * se repère en une ligne de log, sans avoir à inspecter le conteneur.
 * Aucune donnée sensible ici (uniquement des URLs internes).
 */
@Component
public class StartupConfigLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigLogger.class);

    private final GatewayProperties properties;

    public StartupConfigLogger(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        GatewayProperties.Services s = properties.services();
        log.info("Services aval configurés : identity={}, banking={}, payment={}, notification={}",
                s.identity(), s.banking(), s.payment(), s.notification());
        // Sécurité : où le gateway ira chercher la clé publique, et quel issuer il exige
        log.info("Validation JWT : issuer={}, jwks={}",
                properties.security().issuer(), properties.security().jwksUri());
        log.info("Routes reservees ADMIN : {}", properties.security().adminPaths());
    }
}
