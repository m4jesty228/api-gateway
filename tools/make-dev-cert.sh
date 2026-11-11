#!/bin/sh
# Génère un certificat AUTO-SIGNÉ pour tester le TLS du gateway en local ou en recette (jamais en production :
# en production, utiliser un certificat d'une autorité reconnue, par exemple Let's Encrypt).
# Sortie : tls/gateway.p12 (keystore) et tls/keystore-password.txt (mot de passe aléatoire). Ces fichiers ne
# doivent pas être versionnés ; ils sont donnés à Swarm comme secrets (voir docker-stack.tls.yml).
set -eu
mkdir -p tls
PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n' | head -c 24)"
printf '%s' "$PASS" > tls/keystore-password.txt
keytool -genkeypair -alias gateway -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 \
  -dname "CN=${1:-localhost}" -ext "san=dns:${1:-localhost},ip:127.0.0.1" \
  -storetype PKCS12 -keystore tls/gateway.p12 -storepass "$PASS" -keypass "$PASS"
echo "Keystore créé : tls/gateway.p12 (valable 365 jours)"
