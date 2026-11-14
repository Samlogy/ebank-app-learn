# EBANK : synthèse et guide de test manuel (curl)

Ce document a deux parties :

1. **Synthèse** : ce que fait chaque partie du projet (backend, auth, infra, front-web).
2. **Tests manuels** : une session curl complète, à copier-coller dans un seul terminal, qui couvre tous les modules du backend, les cas qui marchent et les cas d'erreur.

Pré-requis : `curl`, `jq`, `node` 22+ (pour le client WebSocket), Docker.

---

# Partie 1 : synthèse

## 1.1 Vue d'ensemble

```text
 Navigateur (Angular :4200)
   │  1. login Authorization Code + PKCE ─────────► Keycloak :8180 (realm "ebank")
   │  2. REST + "Authorization: Bearer <JWT>"            │ clés de signature (JWKS)
   ▼                                                     ▼
 Backend Spring Boot :8080  (un seul jar = monolithe modulaire)
   ├── PostgreSQL :5432   (comptes, transferts, ledger, outbox, notifications, chat)
   ├── Redis :6379        (rate limits du chat)
   ├── Elasticsearch :9200 (projection analytics)
   ├── Mailpit :8025      (emails de test)
   └── Claude API         (assistant du chat, optionnel en dev)
```

Idée clé : **le backend ne gère pas les utilisateurs**. Pas de table `users`, pas d'endpoint `/login`. Keycloak fait inscription, login, MFA, reset de mot de passe. Le backend vérifie seulement le JWT et lit `sub` (l'ID utilisateur) et `realm_access.roles` (les rôles).

## 1.2 Les modules backend

| Module | Rôle | Ce qu'il expose |
|---|---|---|
| `ebank-common` | Briques partagées : `Money` (BigDecimal + devise, jamais `double`), erreurs RFC 9457 (`ProblemDetail` + `code`), pagination, **outbox transactionnelle**, scheduler | `Outbox`, `OutboxEventHandler`, `ApiException` |
| `ebank-auth` | Sécurité Spring : Resource Server OAuth2, mapping des rôles Keycloak, règles d'accès par chemin | `CurrentUser` (userId, isAdmin), `TokenAuthenticator` (pour STOMP) |
| `ebank-accounts` | Comptes bancaires : création, cycle de vie, historique des modifications | `/api/v1/accounts`, `AccountsApi`, `AccountClosingGuard` |
| `ebank-transactions` | Virements, dépôts, **ledger** (écritures DEBIT/CREDIT), solde calculé, idempotence | `/api/v1/transactions`, `/accounts/{id}/balance`, événements `TransactionCompleted` / `TransactionCancelled` |
| `ebank-notifications` | Écoute les événements de transaction, stocke une notification par utilisateur, push live WebSocket, sinon email/SMS | `/api/v1/notifications` |
| `ebank-analytics` | Projette les événements dans Elasticsearch, calcule résumés, timeline, contreparties | `/api/v1/analytics` |
| `ebank-realtime` | **Une** connexion STOMP par utilisateur (`/ws`), JWT vérifié au `CONNECT`, files par module, présence | `RealtimeMessaging`, `UserQueue` |
| `ebank-chatbot` | Assistant (Claude + outils en lecture seule), passage à un conseiller, file d'attente, chat live | `/api/v1/chat/conversations`, `/api/v1/advisor` |
| `ebank-app` | Point d'entrée, configuration (`application*.yml`), Flyway, tests d'intégration et `ModuleBoundariesTest` | le jar exécutable |

Chaque module métier a la même structure : `api` (public), `application` (services), `domain` (règles), `infrastructure` (JPA, ES...), `web` (controllers, DTO).

**Règle d'architecture** : un module n'appelle un autre module que par son package `api`, jamais ses tables ni ses classes internes. ArchUnit (`ModuleBoundariesTest`) casse le build sinon. Exemple : `transactions` utilise `AccountsApi` pour verrouiller les comptes, mais ne lit jamais la table `accounts`.

## 1.3 Le flux d'un virement, de bout en bout

```text
POST /transactions (Idempotency-Key)
  └─ transactions, UNE transaction SQL (timeout 10 s) :
       1. clé d'idempotence déjà vue ? → rejouer la réponse
       2. SELECT ... FOR UPDATE sur les 2 comptes (ordre fixe → pas de deadlock)
       3. vérifs : propriétaire, ACTIVE, même devise
       4. solde suffisant ? oui → DONE + DEBIT + CREDIT
                            non → CANCELLED, aucune écriture
       5. INSERT outbox_event (TransactionCompleted ou TransactionCancelled)
  COMMIT
        │
        ▼ relay outbox (in-process, toutes les ~secondes, retries 2 s → 15 min)
  ├─ notifications : crée la notif → utilisateur connecté en STOMP ? push : sinon FallbackRequested → email
  └─ analytics     : indexe un document dans Elasticsearch (≈ 1-2 s de retard)
```

Points importants :

- **Le solde n'est jamais stocké** : c'est la somme des écritures du ledger. Un dépôt est équilibré par un compte technique `EXTERNAL-EUR`.
- **Outbox = "at least once"** : chaque handler est idempotent et a sa propre transaction. Si Elasticsearch tombe, les notifications passent quand même.
- **Un compte d'autrui renvoie 404**, pas 403, pour qu'on ne puisse pas deviner les IDs.
- **Un admin voit tout mais ne peut pas débiter le compte d'un client.** Seul l'admin peut activer un compte et faire un dépôt.

## 1.4 Cycle de vie d'un compte

```text
CREATED ──activate (admin)──► ACTIVE ──disable──► DISABLED ──close──► CLOSED (final)
                                 ▲                   │
                                 └──activate (admin)─┘
```

Fermer exige `DISABLED` **et** solde = 0 (c'est `transactions` qui pose ce veto via `AccountClosingGuard`).
Types : `CURRENT`, `SAVING` (devise fiat) et `CRYPTO` (BTC ou ETH). ID public : `EB` + 2 chiffres de contrôle mod 97 + 20 chiffres.

## 1.5 Auth : Keycloak

| Élément | Valeur |
|---|---|
| Realm | `ebank` |
| `ebank-web` | client public du front, Authorization Code + PKCE S256, **pas de password grant** |
| `ebank-api` | client bearer-only, sert d'**audience** (`aud`) obligatoire dans le token |
| `ebank-notifications` | compte de service confidentiel : lit email/téléphone des utilisateurs via l'Admin API |
| `ebank-cli` | **dev uniquement**, créé par `infra/scripts/lib.sh` (ou par la partie 2 ci-dessous) : password grant pour curl |
| Rôles | `CUSTOMER` (par défaut), `ADVISOR` (chat, pas d'accès aux données bancaires), `ADMIN` |
| MFA | TOTP obligatoire pour ADMIN et ADVISOR dans le navigateur (le password grant de `ebank-cli` le contourne) |
| Tokens | access 5 min, refresh à usage unique |

Le backend valide : signature (JWKS), expiration, `iss = http://localhost:8180/realms/ebank`, `aud` contient `ebank-api`.

Accès par chemin : chemins publics (`/actuator/**`, Swagger en dev), autres `/actuator/**` → ADMIN, `/api/**` → authentifié (le contrôle fin propriétaire/admin est dans les services), le reste → refusé.

## 1.6 Infra

| Dossier | Contenu |
|---|---|
| `infra/compose` | `docker-compose.yml` local. Sans profil : postgres, redis, elasticsearch, mailpit, keycloak (+ sa DB). `--profile app` : backend + front. `--profile observability` : Prometheus, Grafana, OTel collector, Jaeger, ELK |
| `infra/vault` | **Vault = source unique de la config et des secrets** de chaque environnement (`ebank/<env>/backend/config`, `.../secrets`, `front-web/config`, `postgres`, `redis`, `keycloak`). Le backend lit Vault lui-même au démarrage (Spring Cloud Vault : jeton en compose, auth Kubernetes en k3s) ; External Secrets Operator copie le reste en Secrets Kubernetes, Reloader redémarre les pods. Une policy par composant et par environnement. En compose, `vault` (mode dev, http://localhost:8200, jeton `dev-root-token`) est rempli par `vault-seed` depuis `infra/vault/local`. |
| `infra/docker` | Dockerfiles multi-stage, non-root (backend 8080/8081, front sur nginx 8080) |
| `infra/keycloak/realms` | `ebank-realm.json`, importé au premier démarrage seulement (sinon `down -v`) |
| `infra/helm/ebank` | Chart Helm pour k3s : `templates/` (postgres, redis, keycloak, backend, front, ingress Traefik, NetworkPolicy, ES et Mailpit en dev), `values.yaml` (défauts type prod) + `values-dev/qua/prod.yaml`. Déploiement : `helm upgrade --install ebank infra/helm/ebank -n ebank-<env> -f values-<env>.yaml`. Backend en 1 replica (broker STOMP en mémoire), pods durcis |
| `infra/ci` | Jenkinsfile (gitleaks → build/tests → Sonar → Semgrep/Trivy + `helm lint` → image → push + `helm upgrade` en dev + ZAP sur master), `security/scan.sh` pour lancer les mêmes scans en local |
| `infra/observability` | Prometheus + alertes, dashboard Grafana, traces OTLP → Jaeger, logs ECS JSON → Filebeat → Logstash → Kibana |
| `infra/scripts` | Tests E2E automatisés (`test-notifications.sh`, `test-analytics.sh`, `test-chat.sh`), `lib.sh`, `stomp-client.mjs` |

Profils Spring : `dev` (valeurs par défaut locales, Swagger public, actuator sur 8080), `qua` / `prod` (variables obligatoires, actuator sur 8081 interne, logs JSON, Swagger coupé en prod).

## 1.7 Front-web (Angular 21)

```text
front-web/src/app/
├── core/      auth (KeycloakService, guards, interceptor), http (erreurs), layout, theme
├── features/  accounts, transactions, notifications, profile, chatbot, analytics, auth
└── shared/    composants, modèles, pipes
```

- Login délégué à Keycloak (`keycloak-js`, PKCE). Token en mémoire, rechargé via la session SSO.
- `authInterceptor` n'ajoute le token **que** pour `environment.apiUrl` et le rafraîchit s'il reste < 30 s.
- État réel des pages : comptes, transactions, profil, thème → branchés sur l'API. Notifications → encore calculées côté navigateur (polling des transferts toutes les 30 s), pas encore sur `/notifications`. Chatbot → transport simulé (`DemoChatTransport`). Analytics → page vide.
- `environment.ts` code en dur `localhost` : il faut une config par environnement pour k3s.

---

# Partie 2 : tests manuels avec curl

Les codes attendus ci-dessous ont été vérifiés le 2026-11-14 contre la stack locale (profil `dev`, sans `ANTHROPIC_API_KEY`).

Lancez tous les blocs **dans le même terminal**, dans l'ordre : chaque bloc réutilise les variables des précédents.

## 2.0 Démarrer la stack

```bash
cd infra/compose
docker compose --profile app up -d --build --wait
# ou : infra seule + backend local
#   docker compose up -d --wait
#   cd backend && ./mvnw -DskipTests package
#   SPRING_PROFILES_ACTIVE=dev java -jar ebank-app/target/ebank-app-0.0.1-SNAPSHOT.jar
cd ../..
```

```bash
export API=http://localhost:8080
export KC=http://localhost:8180
export MAILPIT=http://localhost:8025
export PASSWORD='Test-1234!Ebank'
uuid() { cat /proc/sys/kernel/random/uuid; }

curl -s $API/actuator/health | jq          # {"status":"UP", ...}
curl -s $API/actuator/health/readiness | jq
curl -s $KC/realms/ebank | jq .realm       # "ebank"
curl -s $API/actuator/health | jq .components.vault   # {"status":"UP"} : la config du backend vient de Vault
VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-root-token vault kv get -mount=ebank local/backend/config   # ou l'UI http://localhost:8200
```

## 2.1 Préparer Keycloak (dev uniquement)

Token admin Keycloak (realm `master`) :

```bash
export KC_TOKEN=$(curl -s $KC/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin | jq -r .access_token)
kc() { curl -s -X "$1" "$KC/admin/realms/ebank$2" -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"}; }
```

Client `ebank-cli` avec password grant et audience `ebank-api` (ignore l'erreur 409 s'il existe déjà) :

```bash
kc POST /clients '{
  "clientId": "ebank-cli", "enabled": true, "publicClient": true,
  "standardFlowEnabled": false, "directAccessGrantsEnabled": true,
  "protocolMappers": [{"name": "audience-ebank-api", "protocol": "openid-connect",
    "protocolMapper": "oidc-audience-mapper",
    "config": {"included.client.audience": "ebank-api", "access.token.claim": "true", "id.token.claim": "false"}}]}'
```

Créer les utilisateurs (un suffixe unique pour pouvoir relancer) :

```bash
RUN=$(date +%s)
create_user() { # create_user EMAIL [ROLE]
  kc POST /users "$(jq -n --arg e "$1" --arg p "$PASSWORD" '{username:$e, email:$e, emailVerified:true, enabled:true,
     firstName:"Test", lastName:($e|split("@")[0]), credentials:[{type:"password", value:$p, temporary:false}]}')"
  local id=$(kc GET "/users?username=$1&exact=true" | jq -r '.[0].id')
  [[ -n "${2:-}" ]] && kc POST "/users/$id/role-mappings/realm" "[$(kc GET /roles/$2)]"
  echo "$id"
}
export ALICE_EMAIL=alice.$RUN@ebank.local BOB_EMAIL=bob.$RUN@ebank.local
export ADMIN_EMAIL=admin.$RUN@ebank.local ADVISOR_EMAIL=advisor.$RUN@ebank.local
ALICE_ID=$(create_user $ALICE_EMAIL); BOB_ID=$(create_user $BOB_EMAIL)
create_user $ADMIN_EMAIL ADMIN >/dev/null; create_user $ADVISOR_EMAIL ADVISOR >/dev/null
echo "alice=$ALICE_ID bob=$BOB_ID"
```

Obtenir les tokens. **Ils expirent après 5 minutes** : relancez `tokens` dès que vous voyez un 401 inattendu.

```bash
token_for() { curl -s $KC/realms/ebank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=ebank-cli -d username="$1" -d password="$PASSWORD" -d scope=openid | jq -r .access_token; }
tokens() {
  export ALICE=$(token_for $ALICE_EMAIL) BOB=$(token_for $BOB_EMAIL)
  export ADMIN=$(token_for $ADMIN_EMAIL) ADVISOR=$(token_for $ADVISOR_EMAIL)
}
tokens
```

Inspecter un token (vérifier `iss`, `aud`, `sub`, rôles) :

```bash
jwt() { cut -d. -f2 <<<"$1" | tr '_-' '/+' | base64 -d 2>/dev/null | jq '{iss, aud, sub, exp, roles: .realm_access.roles}'; }
jwt "$ALICE"    # roles contient CUSTOMER
jwt "$ADMIN"    # roles contient ADMIN
```

Petit raccourci pour la suite (affiche le corps puis le code HTTP) :

```bash
call() { # call METHOD PATH TOKEN [JSON] [extra curl args...]
  local m=$1 p=$2 t=$3 b=${4:-}; shift 4 2>/dev/null || shift $#
  curl -s -w '\n→ HTTP %{http_code}\n' -X "$m" "$API$p" -H 'Content-Type: application/json' \
    ${t:+-H "Authorization: Bearer $t"} ${b:+-d "$b"} "$@"
}
```

## 2.2 Module auth : sécurité

| # | Test | Commande | Attendu |
|---|---|---|---|
| A1 | Sans token | `call GET /api/v1/accounts ""` | 401 `UNAUTHORIZED` (ProblemDetail) |
| A2 | Token invalide | `call GET /api/v1/accounts "abc.def.ghi"` | 401 |
| A3 | Token valide | `call GET /api/v1/accounts "$ALICE"` | 200, liste vide |
| A4 | Token expiré | attendre 5 min sans `tokens`, rejouer A3 | 401 |
| A5 | Chemin hors `/api` | `call GET /foo "$ALICE"` | 403 `FORBIDDEN` |
| A6 | Actuator public (dev) | `curl -s $API/actuator/prometheus \| grep ebank_ \| head` | métriques `ebank_*` |
| A7 | Swagger (dev) | ouvrir `$API/swagger-ui.html` | UI |

```bash
call GET /api/v1/accounts ""
call GET /api/v1/accounts "abc.def.ghi"
call GET /api/v1/accounts "$ALICE"
```

## 2.3 Module accounts

Créer des comptes :

```bash
ALICE_ACC=$(call POST /api/v1/accounts "$ALICE" '{"type":"CURRENT","currency":"EUR"}' | tee /dev/stderr | head -1 | jq -r .accountId)
ALICE_ACC2=$(call POST /api/v1/accounts "$ALICE" '{"type":"SAVING","currency":"EUR"}' | head -1 | jq -r .accountId)
BOB_ACC=$(call POST /api/v1/accounts "$BOB" '{"type":"CURRENT","currency":"EUR"}' | head -1 | jq -r .accountId)
ALICE_USD=$(call POST /api/v1/accounts "$ALICE" '{"type":"CURRENT","currency":"USD"}' | head -1 | jq -r .accountId)
echo "$ALICE_ACC $ALICE_ACC2 $BOB_ACC $ALICE_USD"   # format EBxx + 20 chiffres, statut CREATED
```

Cas d'erreur à la création :

```bash
call POST /api/v1/accounts "$ALICE" '{"type":"CRYPTO","currency":"EUR"}'   # 422 CURRENCY_TYPE_MISMATCH
call POST /api/v1/accounts "$ALICE" '{"type":"CURRENT","currency":"BTC"}'   # 422 CURRENCY_TYPE_MISMATCH
call POST /api/v1/accounts "$ALICE" '{"type":"CURRENT","currency":"XYZ"}'   # 422 UNSUPPORTED_CURRENCY
call POST /api/v1/accounts "$ALICE" '{"type":"CURRENT","currency":"eur"}'   # 400 VALIDATION_FAILED (violations)
call POST /api/v1/accounts "$ALICE" '{"currency":"EUR"}'                    # 400 VALIDATION_FAILED
call POST /api/v1/accounts "$ALICE" '{"type":"CRYPTO","currency":"BTC"}'    # 201
```

Lecture et propriété :

```bash
call GET /api/v1/accounts "$ALICE"                       # ses comptes uniquement, plus récents d'abord
call GET "/api/v1/accounts?page=0&size=2" "$ALICE"       # pagination
call GET "/api/v1/accounts?size=500" "$ALICE"            # 400 (max 100)
call GET /api/v1/accounts/$ALICE_ACC "$ALICE"            # 200
call GET /api/v1/accounts/$ALICE_ACC "$BOB"              # 404 ACCOUNT_NOT_FOUND (pas 403 !)
call GET /api/v1/accounts/EB00000000000000000000 "$ALICE" # 404 aussi
call GET /api/v1/accounts "$ADMIN"                       # admin : tous les comptes
```

Activation (admin seulement) :

```bash
call PATCH /api/v1/accounts/$ALICE_ACC/activate "$ALICE"   # 403 FORBIDDEN : un client ne s'active pas
for a in $ALICE_ACC $ALICE_ACC2 $BOB_ACC $ALICE_USD; do call PATCH /api/v1/accounts/$a/activate "$ADMIN"; done   # 200, status ACTIVE
call PATCH /api/v1/accounts/$ALICE_ACC/activate "$ADMIN"   # 409 INVALID_ACCOUNT_STATUS (déjà actif)
```

Modifier le type, historique :

```bash
call PUT /api/v1/accounts/$ALICE_ACC2 "$ALICE" '{"type":"CURRENT"}'   # 200
call PUT /api/v1/accounts/$ALICE_ACC2 "$ALICE" '{"type":"CRYPTO"}'    # 422 CURRENCY_TYPE_MISMATCH
call PUT /api/v1/accounts/$ALICE_ACC2 "$BOB"   '{"type":"SAVING"}'    # 404
call GET /api/v1/accounts/$ALICE_ACC2/history "$ALICE"                # CREATED, ACTIVATED, TYPE_CHANGED (avant/après, changedBy, changedAt)
```

## 2.4 Module transactions

### Dépôts (admin seulement)

```bash
dep() { call POST /api/v1/transactions/deposits "$ADMIN" \
  "{\"receiverAccountId\":\"$1\",\"amount\":$2,\"currency\":\"${3:-EUR}\",\"reference\":\"salaire\"}" -H "Idempotency-Key: ${4:-$(uuid)}"; }

dep $ALICE_ACC 1000                    # 201, status DONE
call GET /api/v1/accounts/$ALICE_ACC/balance "$ALICE"   # 1000.00 EUR

call POST /api/v1/transactions/deposits "$ALICE" "{\"receiverAccountId\":\"$ALICE_ACC\",\"amount\":10,\"currency\":\"EUR\"}" \
  -H "Idempotency-Key: $(uuid)"        # 403 : client ne peut pas déposer
call POST /api/v1/transactions/deposits "$ADMIN" "{\"receiverAccountId\":\"$ALICE_ACC\",\"amount\":10,\"currency\":\"EUR\"}"
                                       # 400 : header Idempotency-Key manquant
dep $ALICE_ACC 10.123                  # 422 INVALID_AMOUNT (3 décimales en EUR : rejeté, jamais arrondi)
dep $ALICE_ACC -5                      # 422 INVALID_AMOUNT
dep $ALICE_ACC 10 USD                  # 422 RECEIVER_NOT_AVAILABLE (compte EUR)
dep $ALICE_USD 50 USD                  # 201
```

### Virements

```bash
tr() { # tr TOKEN FROM TO AMOUNT [CURRENCY] [KEY]
  call POST /api/v1/transactions "$1" \
    "{\"senderAccountId\":\"$2\",\"receiverAccountId\":\"$3\",\"amount\":$4,\"currency\":\"${5:-EUR}\",\"reference\":\"loyer\"}" \
    -H "Idempotency-Key: ${6:-$(uuid)}"
}

TX=$(tr "$ALICE" $ALICE_ACC $BOB_ACC 30.50 | tee /dev/stderr | head -1 | jq -r .transactionId)   # 201 DONE
call GET /api/v1/accounts/$ALICE_ACC/balance "$ALICE"   # 969.50
call GET /api/v1/accounts/$BOB_ACC/balance "$BOB"       # 30.50
tr "$ALICE" $ALICE_ACC $ALICE_ACC2 100                  # 201, virement interne
```

Cas d'erreur :

```bash
tr "$ALICE" $ALICE_ACC $ALICE_ACC 10        # 422 SAME_ACCOUNT
tr "$BOB"   $ALICE_ACC $BOB_ACC 10          # 404 : bob ne possède pas le compte émetteur
tr "$ADMIN" $ALICE_ACC $BOB_ACC 10          # 404 : l'admin ne peut pas débiter un client
tr "$ALICE" $ALICE_ACC $ALICE_USD 10        # 422 RECEIVER_NOT_AVAILABLE (destinataire en USD)
tr "$ALICE" $ALICE_ACC EB00000000000000000000 10   # 422 RECEIVER_NOT_AVAILABLE
tr "$ALICE" $ALICE_ACC $BOB_ACC 0           # 422 INVALID_AMOUNT
tr "$ALICE" $ALICE_ACC $BOB_ACC 999999      # 422 INSUFFICIENT_FUNDS (transfert enregistré CANCELLED)
```

### Idempotence

```bash
KEY=$(uuid)
tr "$ALICE" $ALICE_ACC $BOB_ACC 5 EUR $KEY                 # 201
call POST /api/v1/transactions "$ALICE" \
  "{\"senderAccountId\":\"$ALICE_ACC\",\"receiverAccountId\":\"$BOB_ACC\",\"amount\":5,\"currency\":\"EUR\",\"reference\":\"loyer\"}" \
  -H "Idempotency-Key: $KEY" -D - | grep -iE 'HTTP/|idempotency-replayed'
                                                            # 200 + Idempotency-Replayed: true, solde inchangé
tr "$ALICE" $ALICE_ACC $BOB_ACC 6 EUR $KEY                 # 422 IDEMPOTENCY_KEY_REUSED (requête différente)

KEY2=$(uuid)
tr "$ALICE" $ALICE_ACC $BOB_ACC 999999 EUR $KEY2           # 422 INSUFFICIENT_FUNDS
tr "$ALICE" $ALICE_ACC $BOB_ACC 999999 EUR $KEY2           # même 422 rejoué
```

Test de concurrence (10 virements simultanés, aucun découvert possible) :

```bash
call GET /api/v1/accounts/$BOB_ACC/balance "$BOB"
for i in $(seq 10); do tr "$BOB" $BOB_ACC $ALICE_ACC 10 >/dev/null & done; wait
call GET /api/v1/accounts/$BOB_ACC/balance "$BOB"   # jamais négatif ; les virements en trop sont CANCELLED
```

### Lecture et recherche

```bash
call GET /api/v1/transactions/$TX "$ALICE"     # 200 (émetteur)
call GET /api/v1/transactions/$TX "$BOB"       # 200 (destinataire, transfert DONE)
call GET /api/v1/transactions/$TX "$ADMIN"     # 200 (admin)
call GET "/api/v1/transactions?accountId=$ALICE_ACC&size=50" "$ALICE"
call GET "/api/v1/transactions?sender=$ALICE_ACC&receiver=$BOB_ACC" "$ALICE"
call GET "/api/v1/transactions?transactionId=$TX" "$ALICE"
call GET "/api/v1/transactions" "$BOB" | head -1 | jq '[.items[] | {status, senderAccountId}]'
                                               # les CANCELLED visibles sont ceux de bob (test de concurrence) ;
                                               # le virement refusé d'alice vers bob (999999) n'y est pas
call GET /api/v1/transactions/TXN00000000000000000000000000000000 "$ALICE"   # 404 TRANSACTION_NOT_FOUND (IDs au format TXN...)
```

### Export du ledger (admin, journées clôturées)

Les écritures du jour ne sont pas exportables (`to` doit être avant aujourd'hui, UTC) : pour voir des lignes, exporter une période passée qui en contient, ou vieillir des écritures en base (dev uniquement : `UPDATE ledger_entries SET created_at = created_at - interval '1 day';`).

```bash
YESTERDAY=$(date -u -d yesterday +%F); TODAY=$(date -u +%F)
curl -s -D - -H "Authorization: Bearer $ADMIN" "$API/api/v1/ledger/export?from=$YESTERDAY&to=$YESTERDAY" | head -8
                                               # 200 text/csv, X-Ledger-Entry-Count, en-tête entry_id,...,booked_at
call GET "/api/v1/ledger/export?from=$YESTERDAY&to=$YESTERDAY" "$ALICE"     # 403 FORBIDDEN
call GET "/api/v1/ledger/export?from=$YESTERDAY&to=$TODAY" "$ADMIN"        # 422 DAY_NOT_CLOSED
call GET "/api/v1/ledger/export?from=2026-01-01&to=2026-03-01" "$ADMIN"    # 422 RANGE_TOO_LONG (31 jours max)
```

### Fermer un compte (règle inter-modules)

```bash
call PATCH /api/v1/accounts/$ALICE_ACC2/close "$ALICE"     # 409 INVALID_ACCOUNT_STATUS (doit être DISABLED)
call PATCH /api/v1/accounts/$ALICE_ACC2/disable "$ALICE"   # 200 DISABLED
tr "$ALICE" $ALICE_ACC $ALICE_ACC2 1                        # 422 RECEIVER_NOT_AVAILABLE
call PATCH /api/v1/accounts/$ALICE_ACC2/close "$ALICE"     # 409 ACCOUNT_BALANCE_NOT_ZERO (veto de transactions)
call PATCH /api/v1/accounts/$ALICE_ACC2/activate "$ADMIN"
tr "$ALICE" $ALICE_ACC2 $ALICE_ACC 100                      # vider le compte
call PATCH /api/v1/accounts/$ALICE_ACC2/disable "$ALICE"
call PATCH /api/v1/accounts/$ALICE_ACC2/close "$ALICE"     # 200 CLOSED
call PATCH /api/v1/accounts/$ALICE_ACC2/activate "$ADMIN"  # 409 : CLOSED est final
call GET /api/v1/accounts/$ALICE_ACC2/history "$ALICE"     # toute l'histoire
```

## 2.5 Module notifications

Les événements passent par l'outbox : attendez 1-2 s après un virement.

```bash
tokens
call GET /api/v1/notifications "$BOB"                       # TRANSFER_RECEIVED pour chaque virement reçu
call GET /api/v1/notifications "$ALICE" | head -1 | jq '[.items[].type] | group_by(.) | map({(.[0]): length}) | add'
                                                            # TRANSFER_SENT, TRANSFER_INTERNAL, TRANSFER_CANCELLED, TRANSFER_RECEIVED
call GET /api/v1/notifications/unread-count "$ALICE"        # {"count": n}
call GET "/api/v1/notifications?unreadOnly=true&size=5" "$ALICE"

NID=$(call GET /api/v1/notifications "$ALICE" | head -1 | jq -r '.items[0].id')
call PATCH /api/v1/notifications/$NID/read "$BOB"           # 404 NOTIFICATION_NOT_FOUND (pas à lui)
call PATCH /api/v1/notifications/$NID/read "$ALICE"         # 200, read: true
call POST /api/v1/notifications/read-all "$ALICE"           # {"updated": n-1}
call GET "/api/v1/notifications?size=500" "$ALICE"          # 400
```

Préférences :

```bash
call GET /api/v1/notifications/preferences "$BOB"                                       # email on, SMS off
call PUT /api/v1/notifications/preferences "$BOB" '{"emailEnabled":true}'               # 400 (champ manquant)
call PUT /api/v1/notifications/preferences "$BOB" '{"emailEnabled":false,"smsEnabled":false}'   # 200
```

Emails de repli (utilisateur hors ligne) dans Mailpit :

```bash
curl -s "$MAILPIT/api/v1/search?query=to:%22$ALICE_EMAIL%22" | jq '.messages[] | {Subject, Created}'
# ou ouvrir http://localhost:8025 ; l'ID de compte est masqué, la référence n'apparaît jamais
tr "$ALICE" $ALICE_ACC $BOB_ACC 1 >/dev/null; sleep 3
curl -s "$MAILPIT/api/v1/search?query=to:%22$BOB_EMAIL%22" | jq '.messages | length'   # n'augmente plus : email désactivé
```

### Push live WebSocket / STOMP

Terminal 2 (garder `ALICE` exporté, ou copier sa valeur) :

```bash
node infra/scripts/stomp-client.mjs --token "$ALICE"           # CONNECTED, SUBSCRIBED, puis MESSAGE {...}
```

Terminal 1 : `tr "$ALICE" $ALICE_ACC $BOB_ACC 2` → le terminal 2 reçoit `TRANSFER_SENT` en direct, et alice ne reçoit **pas** d'email.

Cas refusés :

```bash
node infra/scripts/stomp-client.mjs --timeout 5                                    # sans token : ERROR
node infra/scripts/stomp-client.mjs --token bad --timeout 5                        # token invalide : ERROR
node infra/scripts/stomp-client.mjs --token "$ALICE" --subscribe /topic/x --timeout 5   # abonnement interdit : ERROR puis fermeture
```

Après 5 min sans renvoyer de token sur `/app/token`, la connexion est fermée avec le code `4001`.

## 2.6 Module analytics

Elasticsearch a ~1-2 s de retard. Dates en UTC, `yyyy-MM-dd`, 30 derniers jours par défaut, 366 jours max.

```bash
TODAY=$(date -u +%F); MONTH_AGO=$(date -u -d '-30 days' +%F)
call GET /api/v1/analytics/summary "$ALICE"                                   # par devise : incoming, outgoing, internal, net, refused
call GET "/api/v1/analytics/summary?accountId=$ALICE_ACC" "$ALICE"            # internes comptés en entrée/sortie
call GET "/api/v1/analytics/timeline?currency=EUR&interval=DAY&from=$MONTH_AGO&to=$TODAY" "$ALICE"
call GET "/api/v1/analytics/timeline?currency=EUR&interval=WEEK" "$ALICE"
call GET "/api/v1/analytics/counterparties?currency=EUR&direction=OUTGOING&limit=5" "$ALICE"
call GET "/api/v1/analytics/counterparties?currency=EUR&direction=INCOMING" "$ALICE"   # contient EXTERNAL-EUR (dépôt)
call GET /api/v1/analytics/overview "$ADMIN"                                  # stats globales banque
```

Erreurs :

```bash
call GET /api/v1/analytics/overview "$ALICE"                                     # 403
call GET "/api/v1/analytics/summary?from=$TODAY&to=$MONTH_AGO" "$ALICE"          # 422 INVALID_RANGE
call GET "/api/v1/analytics/summary?from=2020-01-01&to=$TODAY" "$ALICE"          # 422 RANGE_TOO_LONG
call GET "/api/v1/analytics/timeline?currency=XYZ" "$ALICE"                      # 422 UNSUPPORTED_CURRENCY
call GET "/api/v1/analytics/counterparties?currency=EUR&limit=50" "$ALICE"       # 422 INVALID_LIMIT
call GET "/api/v1/analytics/timeline" "$ALICE"                                   # 400 (currency obligatoire)
```

Panne Elasticsearch (résilience) :

```bash
docker compose -f infra/compose/docker-compose.yml stop elasticsearch
call GET /api/v1/analytics/summary "$ALICE"            # 503 ANALYTICS_UNAVAILABLE
tr "$ALICE" $ALICE_ACC $BOB_ACC 1                      # 201 : les virements marchent toujours
docker compose -f infra/compose/docker-compose.yml start elasticsearch
# quelques secondes plus tard le relay outbox rattrape : le virement apparaît dans summary
```

Voir les documents indexés :

```bash
curl -s "localhost:9200/ebank-transactions/_search?size=3" | jq '.hits.hits[]._source'   # pas de référence ni d'email
```

## 2.7 Module chatbot (client + conseiller)

Sans `ANTHROPIC_API_KEY`, un assistant scripté répond aux mots-clés (balance/solde, accounts/comptes, transactions, advisor/conseiller).

```bash
tokens
CHAT=/api/v1/chat/conversations
msg() { call POST "$CHAT/$2/messages" "$1" "{\"clientMessageId\":\"$(uuid)\",\"text\":\"$3\"}"; }

C=$(call POST $CHAT "$ALICE" | head -1 | jq -r .id); echo $C   # state BOT
call POST $CHAT "$ALICE" | head -1 | jq -r .id                  # même id : une seule conversation ouverte

call GET $CHAT/$C ""                                            # 401
call GET $CHAT/$C "$BOB"                                        # 404 CONVERSATION_NOT_FOUND
call GET /api/v1/advisor/queue "$ALICE"                         # 403 (pas ADVISOR)

msg "$ALICE" $C "What is my balance?"                           # 201
sleep 3; call GET "$CHAT/$C/messages?afterSeq=0" "$ALICE"       # réponse ASSISTANT avec soldes, comptes masqués (…1234)
```

Idempotence et validation des messages :

```bash
K=$(uuid)
call POST $CHAT/$C/messages "$ALICE" "{\"clientMessageId\":\"$K\",\"text\":\"hello\"}"   # 201
call POST $CHAT/$C/messages "$ALICE" "{\"clientMessageId\":\"$K\",\"text\":\"hello\"}"   # 200 (rejoué, stocké une fois)
call POST $CHAT/$C/messages "$ALICE" '{"clientMessageId":"x","text":"hi"}'               # 400 (id 8-64 caractères)
call POST $CHAT/$C/messages "$ALICE" "{\"clientMessageId\":\"$(uuid)\",\"text\":\"$(head -c 2100 /dev/zero | tr '\0' a)\"}"  # 400 (> 2000)
MID=$(call GET "$CHAT/$C/messages" "$ALICE" | head -1 | jq -r '[.[] | select(.senderType=="CUSTOMER")][0].id')
call DELETE $CHAT/$C/messages/$MID "$ALICE"                                               # suppression douce
```

Passage au conseiller :

```bash
call POST $CHAT/$C/handoff "$ALICE"                     # state WAITING_ADVISOR, handoffReason USER_REQUEST
sleep 3; call GET /api/v1/advisor/queue "$ADVISOR"      # la conversation, avec un résumé de l'assistant
call GET /api/v1/advisor/conversations/$C/messages "$ADVISOR"   # transcript complet
call POST /api/v1/advisor/conversations/$C/claim "$ADVISOR"     # WITH_ADVISOR
call POST /api/v1/advisor/conversations/$C/claim "$ADVISOR"     # 409 ALREADY_ASSIGNED
call POST /api/v1/advisor/conversations/$C/messages "$ADVISOR" "{\"clientMessageId\":\"$(uuid)\",\"text\":\"Bonjour, je regarde.\"}"
call GET "$CHAT/$C/messages?afterSeq=0" "$ALICE"        # alice voit le message ADVISOR
call POST $CHAT/$C/read "$ALICE" '{"seq": 1}'           # 204, accusé de lecture
call GET /api/v1/advisor/conversations "$ADVISOR"       # assignées à moi
call POST /api/v1/advisor/conversations/$C/resolve "$ADVISOR"   # RESOLVED
call POST $CHAT/$C/back-to-bot "$ALICE"                 # BOT
call POST $CHAT/$C/resolve "$ALICE"                     # RESOLVED
```

Escalade par l'assistant et assignation automatique (conseiller en ligne) :

```bash
# Terminal 2 : le conseiller "en ligne" = abonné à /user/queue/chat
node infra/scripts/stomp-client.mjs --token "$ADVISOR" --subscribe /user/queue/chat
# Terminal 1
B=$(call POST $CHAT "$BOB" | head -1 | jq -r .id)
msg "$BOB" $B "I want to talk to an advisor"
sleep 3; call GET $CHAT/$B "$BOB"                        # handoffReason LLM_ESCALATION, assignée au conseiller
```

Rate limit (Redis, 20 messages/minute) :

```bash
for i in $(seq 25); do msg "$BOB" $B "spam $i" | tail -1; done   # les derniers en 429
```

## 2.8 Vérifications en base (outbox, ledger)

```bash
psql() { docker compose -f infra/compose/docker-compose.yml exec -T postgres psql -U ebank -d ebank -c "$1"; }
psql "SELECT event_type, status, attempts FROM outbox_event ORDER BY occurred_at DESC LIMIT 10;"   # PUBLISHED
psql "SELECT status, count(*) FROM outbox_event GROUP BY status;"
psql "SELECT transfer_id, type, account_id, amount FROM ledger_entries ORDER BY created_at DESC LIMIT 6;"  # paires DEBIT/CREDIT
psql "SELECT account_id, sum(CASE WHEN type='CREDIT' THEN amount ELSE -amount END) FROM ledger_entries GROUP BY account_id;"
psql "SELECT action, changed_by FROM account_history ORDER BY changed_at DESC LIMIT 10;"
```


### Outbox via RabbitMQ (optionnel)

```bash
EBANK_OUTBOX_RABBITMQ_ENABLED=true docker compose -f infra/compose/docker-compose.yml --profile app --profile broker up -d --build --wait
curl -s $API/actuator/health | jq .status                                   # UP : le broker compte dans la santé
# faire un virement (2.4), puis :
docker compose -f infra/compose/docker-compose.yml exec rabbitmq rabbitmqctl -q list_queues name messages
                                               # une file ebank.<handler> par handler, et sa .dlq (vides si tout va bien)
psql "SELECT event_type, status FROM outbox_event ORDER BY occurred_at DESC LIMIT 3;"   # PUBLISHED dès la confirmation du broker
```

## 2.9 Observabilité

```bash
curl -s $API/actuator/prometheus | grep -E '^ebank_(outbox|notifications|security|chatbot)' | head -20
curl -s $API/actuator/prometheus | grep -E 'http_server_requests_seconds_bucket.*le="1.0"' | head -3   # le seuil du SLO de latence
infra/ci/security/scan.sh rules       # règles d'alerte : syntaxe + tests promtool (SLO, Vault, dead letters)
curl -s $API/actuator/health/liveness
docker compose -f infra/compose/docker-compose.yml --profile observability up -d --wait
# Prometheus :9090, Grafana :3000 (admin/admin), Jaeger :16686 (backend avec OTLP_TRACES_ENABLED=true), Kibana :5601
```

## 2.10 Tests automatisés équivalents

Une fois les tests manuels compris, les scripts refont tout en ~1 min chacun :

```bash
infra/scripts/test-notifications.sh   # 37 vérifications
infra/scripts/test-analytics.sh       # 41 vérifications (SKIP_OUTAGE=1 pour ne pas couper ES)
infra/scripts/test-chat.sh            # 38 vérifications
cd backend && ./mvnw verify           # unitaires + ArchUnit + intégration Testcontainers
cd front-web && npx ng test --watch=false
```

## Récapitulatif des codes d'erreur

| HTTP | Codes |
|---|---|
| 400 | `VALIDATION_FAILED` (liste `violations`), header manquant, page > 100 |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` (rôle manquant : activate, deposit, overview, ledger export, advisor ; chemin hors `/api`) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `NOTIFICATION_NOT_FOUND`, `CONVERSATION_NOT_FOUND` (aussi pour les ressources d'autrui) |
| 409 | `INVALID_ACCOUNT_STATUS`, `ACCOUNT_BALANCE_NOT_ZERO`, `CONCURRENT_MODIFICATION`, `ALREADY_ASSIGNED` |
| 422 | `INVALID_AMOUNT`, `UNSUPPORTED_CURRENCY`, `CURRENCY_MISMATCH`, `CURRENCY_TYPE_MISMATCH`, `SAME_ACCOUNT`, `ACCOUNT_NOT_ACTIVE`, `RECEIVER_NOT_AVAILABLE`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_RANGE`, `RANGE_TOO_LONG`, `INVALID_LIMIT`, `DAY_NOT_CLOSED` |
| 429 | rate limit du chat |
| 503 | `ANALYTICS_UNAVAILABLE`, `SERVICE_UNAVAILABLE` |
