# eegfaktura-admin-backend

> Registration and administration backend for the eegfaktura suite.

Provides the REST and gRPC APIs behind the EEG registration/admin web UI:
onboarding of renewable energy communities (EEGs) and participants,
metering-point administration, grid-operator lookup, and administrative updates
to EEG configuration and process state. Multi-tenant; integrates with Keycloak
for user and realm management. (Container image: `eeg-registration-backend`.)

Part of the **eegfaktura** suite — an open-source billing and management platform
for Austrian renewable energy communities (*Erneuerbare-Energiegemeinschaften*, EEG).

## Tech stack

- **Scala 2.13**, built with **sbt**
- **Apache Pekko 1.2** — Actors, Pekko HTTP, Pekko gRPC
- **Slick 3.5** + HikariCP over **PostgreSQL**
- **Keycloak** admin/OIDC integration; Nimbus JOSE+JWT for token validation
- Circe (JSON), sttp client, Logback
- Packaged via sbt-native-packager (base image `eclipse-temurin:17-jre`)

## Key components

- `src/main/scala/at/ourproject/`
  - `routes/` — HTTP routes (Admin, Eeg, Registration) + Keycloak JWT authenticator
  - `services/` — actor-based business logic (`RegisterService`, `AdminService`)
  - `dao/` — repositories (EEG, participants, operators, metering, tenant users)
  - `keycloak/` — Keycloak admin & OAuth2 client integration
- `src/main/protobuf/` — gRPC contracts (`register.proto`, `master.proto`, `ponton.proto`)
- Entry point: `Registration.scala` (binds HTTP on `:8085`)

## Build

```bash
sbt clean compile
sbt test
```

## Run

Local:

```bash
sbt run        # HTTP/REST on 0.0.0.0:8085
```

Docker (image built via the native packager):

```bash
sbt Docker/publishLocal
docker run -p 8085:8085 -v ./application.conf:/conf/application.conf \
  <image> -Dconfig.file=/conf/application.conf
```

## Configuration

Environment variables (names only — never commit secret values):

| Variable | Purpose |
|---|---|
| `KEYCLOAK_URL`, `KEYCLOAK_REALM` | Keycloak server and realm |
| `KEYCLOAK_AUDIENCE` | expected JWT audience |
| `KEYCLOAK_ADMIN_CLI_AUDIENCE`, `KEYCLOAK_ADMIN_CLI_SECRET` | admin-cli client for realm operations |
| `REGISTER_SERVICE_HOST_EEG` | gRPC host of `eegfaktura-backend` (EEG registration) |
| `REGISTER_SERVICE_HOST_PONTON` | gRPC host of `eegfaktura-eda-xp` (Ponton/KEP registration) |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_DBNAME`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `LOG_LEVEL` | log level |

Exposed port: **8085** (HTTP/REST).

## Dependencies

- **PostgreSQL** — data store (Slick)
- **Keycloak** — user/realm management and JWT validation
- **eegfaktura-backend** (gRPC) — EEG registration
- **eegfaktura-eda-xp** (gRPC) — Ponton/KEP partner registration

## License

GNU Affero General Public License v3.0 (AGPL-3.0) — see [`LICENSE`](LICENSE).
