# Changelog

All notable changes to **eegfaktura-admin-backend (Scala/Pekko admin API)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

### Fixed
- **Registering an existing EEG is refused** (261015-ca1). `POST /eeg/register` for a tenant that already exists answered
  success and attached the new admin to that community. Now, when the backend reports the tenant as existing
  (`RegisteredEegReply.status` 409), the registration stops: HTTP 409 with `{"code":409,"message":…}` and the text
  „EEG ist bereits registriert (Tenant …, RC-Nummer …). Es wurde nichts geändert.“, no call to eda-xp (Ponton), no change in
  Keycloak. Any other status than 201 from the backend now also skips the Ponton call. The tenant goes to the backend
  without surrounding spaces. A retry after a partly failed registration is refused too: accounts and Ponton are then
  added on the registration pages for accounts and Ponton.

### Contract
- `POST /eeg/register`: new answer HTTP 409 (caller: admin, which shows the message; admin PR of 261015-ca1). Needs the
  backend version that answers 409 (backend PR of 261015-ca1); with an older backend nothing changes. Deploy order: free.

## [1.2.0] – 2026-10-31

### Added
- **Health endpoints `GET /healthz` and `GET /readyz` for Kubernetes probes** on the HTTP port 8085, without a bearer
  token. `/healthz` (liveness) answers 200 `ok` and checks nothing, so a database outage does not restart the pod.
  `/readyz` (readiness) answers 200 `ok` when the database answers `SELECT 1` within 2 s, otherwise 503 `unavailable`.
  The check uses its own short-lived connection (not the application's pool) with driver connect, login and query
  timeouts; one check runs at a time and callers within a second share its result. If Postgres refuses the ping with
  SQLSTATE 53300 (too many connections), `/readyz` still answers 200, because the application's pool keeps its
  connections. A failure or refusal is logged (warn) only when the state changes. All other paths still need a token.
- **Structured logging (JSON) and an access line per request** (eegfaktura-dev logging concept W-261008-ca2; tracking
  admin-backend 261008-ca2). **This changes what operators and Dynatrace receive from the admin-backend:**
  - One logging setup (`at.ourproject.logging`, `logback.xml`) for the application's and Pekko's loggers. In the image
    every line is now **one JSON object on stdout** with ECS field names: `@timestamp` (UTC, ms), `log.level`
    (`trace`/`debug`/`info`/`warn`/`error`), `log.logger`, `service.name` (`eegfaktura-admin-backend`),
    `service.version`, `message`, exceptions as `error.type`/`error.message`/`error.stack_trace` in the same line. Only
    these fields and the access fields below are written; Pekko's MDC entries (`pekkoSource`, `sourceThread`, ...) are
    dropped. The former text pattern (`date level [thread] logger - msg`) is gone; no new library (own encoder on
    logback and the Jackson already in use).
  - Environment variables: `LOG_LEVEL` (default `info`; `trace`, `debug`, `info`, `warn`/`warning`, `error`) now sets
    the root level, including Pekko; before it only reached Pekko. `LOG_LEVEL_APP` (application loggers) now defaults
    to `LOG_LEVEL`. **An unknown value (e.g. `OFF`) stops the start** (exit code 2); an empty value counts as unset (the default applies), as in the other services.
    `LOG_LEVEL_HTTP_CLIENT` (same values, default `warn`) sets the level of the Keycloak admin client's HTTP stack
    (`org.apache.http`, `org.jboss.resteasy`); it does **not** follow `LOG_LEVEL`, because its debug lines carry
    credentials. Its raw traffic and headers (`org.apache.http.wire`, `org.apache.http.headers`) are always off. The
    old `log_slick_action` variable no longer reaches these loggers (it still sets `com.zaxxer.hikari`). The Slick loggers (`slick.*`: SQL, bound parameters, result rows) stay at
    `info` and do not follow `LOG_LEVEL` either; only their own `log_*` variables (e.g. `log_jdbc_parameter`) raise one. `LOG_FORMAT` (`json`, the default also
    when run from source; `text` for a readable line). `SERVICE_VERSION` (set by the image build: `sha-<short>` of the built
    commit, the git tag on a tag build; `dev` from source). A release re-tags a dev-tier image as `vX.Y.Z` without a
    rebuild (gitops `promote-image.yml`), so released images log the `sha-<short>` of their build, not `vX.Y.Z`.
  - Pekko always logs through SLF4J with the SLF4J logging filter, `pekko.loglevel` `DEBUG` (logback decides) and
    `stdout-loglevel` `OFF`; this overrides the mounted `application.conf`, so its `loglevel`/`stdout-loglevel`/`loggers`
    no longer have an effect.
  - **Access line:** one line per request after the token check, `event.action=http.access`, with
    `http.request.method`, `http.route` (the route template, e.g. `/eeg/accounts`; left out when no route matched,
    also for requests refused before the route, message then shows `-`), `http.response.status_code`,
    `event.duration` (nanoseconds), `event.outcome` (`success`, `refused` for 401/403, `failure`), `request.id` (the
    caller's `X-Request-Id` when it is a plain token of up to 128 characters, otherwise generated; returned as response
    header `X-Request-Id`), `trace.id`/`span.id` (from a valid W3C `traceparent`), `tenant.id` (the tenant the route
    acts on, from the query or the body, upper case) and `client.id` (the token's `azp`, the Keycloak client - not a
    person). No user id, name, e-mail, user agent, IP or query values (no user tracking). `/healthz` and `/readyz` are
    left out. The access logger `at.ourproject.access` is fixed at `info`: `LOG_LEVEL`/`LOG_LEVEL_APP` `warn` or
    `error` do not switch the access line off. A request that runs past Pekko's `request-timeout` is logged with
    the 503 the server answers at that moment (not the route's later status); a route that completes after the timeout
    writes no second line. Limit: when the route completes within milliseconds of the timeout, the line can in rare
    cases show the other status than the one the client got (Pekko decides after the access log).

### Fixed
- **The token check no longer logs a token prefix or the user id** (eegfaktura-dev tracking admin-backend 261008-ca2).
  At debug level it logged the first 8 characters of every token and the token's `sub` on every request.
- **Admin master-data updates: a refused update is reported as a failure** (eegfaktura-dev tracking admin-backend
  261005-ca13). `AdminService` mapped every answer of the backend's gRPC `updateValue` to success, also when the backend
  refused the update with status 501/502 and wrote nothing; `/admin/master/update`, `/update/participant` and
  `/update/eeg` then answered 200 (or 500 on the follow-up read). Now only status 200/201 counts as success; any other
  status answers 400, as a failing gRPC call already did. The admin web shows the error (eegfaktura-admin 261005-ca1).
- **Registration: a Keycloak error in the check "does the user already exist" is no longer read as "does not exist"**
  (eegfaktura-dev tracking admin-backend 261005-ca4, part). `checkUserAlreadyExist` turned every Keycloak error into
  `false`, so the registration went on to create a new user. Now only an empty search result means "does not exist";
  an error ends the registration step with error code 510 in the body (HTTP 400, "Creating Keycloak User failed!"), as
  other Keycloak errors there already did. The error path also no longer reads the failed check a second time (that
  would have thrown again while logging and left the request without an answer). Not part of this fix: compensation
  of the steps already done — the community then stays registered in the backend and at Ponton without a user; the
  operator adds the user with addTenant/addAccount instead of registering again.
- **Build: sbt 1.8.3 → 1.10.11 and Scala 2.13.9 → 2.13.18** (eegfaktura-dev tracking admin-backend 261028-mm1). The
  image shipped `scala-library` 2.13.9, although the dependencies are built against 2.13.18 — sbt 1.8.3 forced the
  library down to the compiler version, which can end in a `NoSuchMethodError` at runtime. Now compiler and library are
  2.13.18. The newer resolver honours an exclusion of the RESTEasy BOM, so the unused `com.github.java-json-tools` jars
  `btf`, `jackson-coreutils` and `msg-simple` leave the image. sbt 1.10.11 is the version eda-xp uses; it also runs on
  Java 21. CI and image stay on Java 17.
- **CI: the Snyk Code scan fails visibly when it cannot run.** An invalid `SNYK_TOKEN` ended the weekly and
  push scan green without any result (`continue-on-error`); now only „issues found“ keeps the run green (the
  issues go to Code Scanning), an authentication or tool error fails it. The Snyk CLI is pinned to 1.1308.1
  instead of the latest version.

### Security
- **CI: the release workflow pins its actions by commit SHA** (eegfaktura-dev tracking W-261028-mm6). `rolling-release.yml`
  builds and pushes the shipped image, but used floating tags (`@v4`, `@v3`, …) and an unpinned Java 17; a moved tag ran in the job that holds
  the push credentials. Now every action is pinned to a commit, the same SHAs `pr-checks.yml` and `security-scan.yml` already
  use where the action is the same, otherwise the commit the floating tag pointed to on 2026-10-28. Java is fixed to
  `17.0.19+10` and `setup-java` moves from v4 to v6.0.1, both as in `pr-checks.yml`.

## [1.1.1] – 2026-10-25

### Changed
- Tests: regression test for the bearer token checks (`KeycloakJwtAuthenticatorSpec`,
  vfeeg-development/eegfaktura-platform#183). A valid RS256 token is accepted, the issuer compared
  case-insensitively; rejected are tokens expired 30 s ago (only the service's own `exp` check catches
  that, nimbus allows 60 s skew) or long ago, without `exp`, with a wrong `iss` or `aud`, signed with a
  foreign key, with an unknown `kid`, RS512, HS256 signed with the RSA public key, `alg=none` and
  malformed tokens. Each negative case is the known-good token with one change. The JWKS comes from a
  local HTTP server on 127.0.0.1; production code is unchanged.

### Security
- Removed `keycloak-adapter-core` 25.0.3 (vfeeg-development/eegfaktura-platform#190). Nothing used it;
  the Keycloak admin API calls go through `keycloak-admin-client` 26.0.6 as before. It brought
  `bcprov-jdk18on` 1.74 (2 CRITICAL, 1 HIGH) and `keycloak-server-spi-private` 25.0.3 (1 HIGH) into the
  image, together with `bcpkix`/`bcutil`, `keycloak-crypto-default` and `keycloak-server-spi`.
- `commons-io` 2.11.0 → 2.20.0 (CVE-2024-47554, HIGH; platform#190). It comes through
  `keycloak-admin-client` → RESTEasy multipart → `apache-mime4j-storage`, not through the adapter.
- Dependency updates for known HIGH CVEs found by the security scan
  (vfeeg-development/eegfaktura-platform#183), all patch/minor:
  - Jackson (`jackson-core` and its family, via Keycloak, including the Jakarta RS modules of the
    Keycloak admin client) 2.17.2 / 2.15.4 → 2.18.12.
  - gRPC (`grpc-netty-shaded` and the other `io.grpc` libraries, via pekko-grpc) 1.67.1 → 1.75.0, the
    version pekko-grpc 1.2.0 uses. The generated gRPC code is unchanged; a new test runs the generated
    client over h2c against the generated server handler (`GrpcClientRoundTripSpec`).
  - `nimbus-jose-jwt` (JWT validation) 9.31 → 9.37.4. Bearer token checks (RS256 only, `exp`, `iss`,
    `aud`) behave as before.
  - `protobuf-java` pinned to 3.25.8 (scalapb-runtime declares 3.19.6; at runtime 3.25.x was already used).
  - `jawn-parser` (JSON parser behind circe) 1.4.0 / 1.6.0 → 1.7.0.

### Tests
- `RegisterServiceSpec` asserts behaviour instead of printing: the JSON of `POST /eeg/register` as the admin web sends it (decoding, enumeration names, rejection of unknown values and of a missing `user`) and `registerPonton` against a recording gRPC stub (no call for `NONE` or offline, upper-cased RC number and credentials, communication type `KEP`, failure on a non-200 reply).

### CI
- `rolling-release.yml`: no image build on a draft pull request — it runs when the pull request is marked ready for review (`ready_for_review`) and on every later push to it; `pr-checks.yml` still checks drafts. Pushes, tags and the deploy dispatch are unchanged.

## [1.1.0] – 2026-10-21

### Added
- **Several logins per community** (platform#166). `POST /eeg/register` takes an optional list `accounts`
  (first name, last name, e-mail): exactly these get a login, all with the groups `EEG_ADMIN` and `EEG_OWNER`; the
  contact (`user`) only if listed. An e-mail that already has an account only gets the community added. The answer
  reports each account (`created`, `linked`, `failed` with the reason); the community is registered even if an
  account fails. Without `accounts` the contact gets the login, as before (that path still stores `rcNumber` as
  tenant; the list path stores `tenant`, the value the backend checks — the admin form sets both alike).
- `POST /eeg/account?tenant=…` adds a login to an existing community (same rules); `GET /eeg/accounts?tenant=…`
  lists the logins of a community (name, e-mail, username, enabled, password not yet set). Both only for the
  superuser. Keycloak cannot search the tenant list in the attribute, so the list reads all users page by page.
- An existing account is only linked if its e-mail address is confirmed; otherwise the account is reported as
  failed („bitte in Keycloak prüfen“). Users can change their own e-mail: an unconfirmed address could belong to
  someone who took the address of the person meant. Linking no longer marks the address as confirmed. This also
  applies to the contact's login at registration without a list (then 510 with the reason).
- An existing account that already has a community but not `EEG_ADMIN` (e.g. a member login) is not linked: the
  groups hold for all its communities, it would become admin there too (decided 21.10.2026). A missing `EEG_OWNER`
  is added (it grants nothing today). It is reported as
  failed („bitte in Keycloak klären“); `/eeg/account` answers 409 for this and for an unconfirmed e-mail.
- A registration with a list of accounts and an invalid tenant is refused (400) before the community is registered.
- The tenant of `/eeg/account`, `/eeg/accounts` and of the registration's accounts is trimmed and must consist of
  letters, digits, `_` and `-` (else 400 / `failed`); the registration's accounts get the community's `tenant` (the
  value the backend checks in the token). The tenant attribute of a new account is written as JSON (it was built
  from the text).
- New accounts keep the random temporary password and the Keycloak mail to set one's own password (no fixed start
  password). If Keycloak cannot send the mail (e.g. no SMTP in the realm) it is only logged; the list then shows
  „password not yet set“.
- `/eeg/register`, `/eeg/account` and `/eeg/accounts` wait up to 50 s for Keycloak (other routes 10 s; below the 60 s idle and proxy timeouts); the groups are
  read once per registration.

### Changed
- Code cleanup without behaviour change (vfeeg-development/eegfaktura-platform#183): two placeholder test
  cases removed, unused imports, private values and locals removed, deprecated `Map.mapValues` replaced; the
  build reports deprecations (`-deprecation`, warnings only). PR checks run one job (`full`: clean build, tests,
  staged image) instead of two; the artifact `test-results-unit` is no longer produced.

### Fixed
- A generated username that is already taken (same name prefix) no longer fails the account: `2` … `9` is appended.
  A username the operator gave is used as it is (taken: error, as before).
- Linking an existing account without a password dropped the required action „update password“ again (the
  attributes were written after the password with the representation read before).
- Adding a community to an existing account reported success also when Keycloak failed; it now reports the error.
- Adding a community to an account whose tenants are stored one value per tenant (`"RC100100"`, `"GC106668"`, as
  set in the Keycloak console or the realm import) replaced all its tenants with the new one; an account without a
  tenant lost its other attributes. Both forms are now read and kept (also for `GET /eeg/addTenant`).

### Security
- The Keycloak `admin-cli` client secret no longer has a default in `application.conf`
  (vfeeg-development/eegfaktura-platform#183). `KEYCLOAK_ADMIN_CLI_SECRET` is now required: without
  it (or with an empty value) the service logs an error and refuses to start. All known deployments
  (prod, pilot, `eegfaktura-docker-compose`, the `eegfaktura-dev` stack) already set it. The unused
  `keycloak.json` (same secret) and a commented-out legacy config block with old secrets were
  removed. The values remain in the git history: **operators must rotate the `admin-cli` secret in
  Keycloak** for every zone and update the Kubernetes secret / compose `.env`.

## [1.0.6] – 2026-10-18

### Added
- CI: PR checks and security scan. Every pull request runs the tests and a clean build with the
  staged Docker image (`pr-checks.yml`); `security-scan.yml` looks for leaked secrets in new
  commits, vulnerable dependencies and Dockerfile/Kubernetes misconfigurations. The dependency and
  misconfiguration scans only report for now and do not block; a leaked secret fails the run. Image
  build and deploy (`rolling-release.yml`) are unchanged.
- CI: every run keeps its scan and test results as artifacts for 30 days (`scan-*`,
  `test-results-<job>`, `known-errors-<job>`) for a CI report collector; the secret scan keeps
  outcomes and counts only.

### Security
- PostgreSQL JDBC driver 42.2.5 → 42.7.13. The old driver has known CVEs; with our fixed
  connection settings (server, database and credentials come from the configuration, no
  user-controlled JDBC URL or connection properties) they are not exploitable, so this is a
  hygiene update. Slick 3.5.1 / HikariCP are unchanged and work with 42.7.

## [1.0.5] – 2026-10-17

### Security
- No credentials or personal data in the logs of the registration and Keycloak paths: creating
  a Keycloak user no longer logs the initial password, registering an EEG logs only RC number
  and tenant instead of the whole request (bank and contact data), and the Keycloak client
  logs the user id instead of the e-mail address. Error messages of a failed user creation no
  longer contain the e-mail address either.

### Changed
- The mail transport for Ponton is no longer supported. With `pontonCommType` `MAIL`,
  `POST /eeg/register` (online EEG) answers HTTP 400 and `POST /eeg/ponton` answers HTTP 200
  with status 400 in the response body, both with "MAIL-Transport wird nicht mehr unterstützt"
  and before anything is registered.
- `POST /eeg/ponton` with `pontonCommType` `NONE` answers HTTP 200 with status 400 in the
  response body ("Keine Ponton-Anbindung gewählt") and no longer registers `NONE` as the type
  in eda-xp. `POST /eeg/register` with `NONE` is unchanged (no Ponton registration).
- Log level defaults to `INFO` for the application loggers and for Pekko; both were `DEBUG` in
  the packaged configuration. `LOG_LEVEL_APP` (new, `at.ourproject.*`) and `LOG_LEVEL` (Pekko)
  switch `DEBUG` on per deployment.

### Fixed
- Creating a Keycloak user without an explicit username no longer fails for last names shorter
  than six characters (or first names shorter than two); the generated username takes up to six
  and up to two characters. Configuring an existing user without a username no longer fails for
  one-letter last names either.
- `POST /eeg/ponton` reports status 200 in the response body (HTTP status is always 200; the
  admin UI does not show the body status yet) only after a successful registration and EEG
  update; it reported 500 even on success. If the backend rejects the EEG update, the body
  carries the backend's status and message instead of reporting success.
- Registering an online EEG at Ponton now sends the communication type `KEP` to eda-xp. The type
  was missing, so eda-xp stored an empty type: it started a mail worker for the tenant and
  ignored the tenant after a restart. Tenants already registered that way have to be corrected
  in eda-xp separately.
- A Keycloak error in `GET /eeg/addTenant` no longer stops the registration service, after which
  every `/eeg/*` request timed out until the pod restarted. The request now gets status 500 in
  the response body (HTTP 200, as before). In
  addition, the registration service now resumes after any exception in a request, so one
  failing request no longer takes down all `/eeg/*` endpoints. This covers exceptions only: a
  Keycloak call that hangs still blocks the service until it returns.
- The test sources compile again (`RegisterServiceSpec` used an outdated model).

### Removed
- The unused endpoint `GET /eeg/users` (no caller) and the code only it used.
- An unused spreadsheet in `src/main/resources` that was packaged into the image.

## [1.0.4] – 2026-10-05

### Security
- New Keycloak users no longer get a fixed fallback password when the request carries
  none, and configuring an existing user without credentials no longer assigns one
  either. Both now get a random temporary password and a Keycloak mail to set their own
  (`UPDATE_PASSWORD`); if the mail cannot be sent, the operator sets the password in the
  Keycloak console. If the credentials of an existing user cannot be read, nothing is
  changed — previously that error path assigned the fallback password even to users who
  already had one.

### Added
- CI builds `env/**` branches and deploys the resulting image into the matching feature
  environment (ADR-0008): a push to `env/<name>` pins this service in namespace `env-<name>`
  to that branch's `sha-…` image. Previously only the default branch, tags and `preview/**`
  produced an image at all. The environment itself is still provisioned manually.

### Fixed
- The Docker build now publishes a `sha-<short>` tag. Unlike most services, this repo is
  packaged by sbt-native-packager rather than `docker/metadata-action`, which never produced
  that tag — while both the preview deploy (ADR-0007) and the new env deploy (ADR-0008) pin
  exactly it, so every such deploy would have ended in `ImagePullBackOff`. Found on
  2026-09-26 when the same defect hit eegfaktura-eda-xp, the other sbt-packaged service.
- A build from a `preview/**` or `env/**` branch no longer overwrites the moving tags
  `latest` and `0.2.11` in the development tier (`dockerUpdateLatest` is off for those
  builds). The dev zone pulls `eeg-registration-backend:latest`, so a feature build would
  silently have become the dev zone's next image. Default-branch and tag builds are
  unchanged.

## [1.0.3] – 2026-09-09

### Security
- The admin API now requires the `superuser` realm role on all three route prefixes
  (`admin`, `vfeeg`, `eeg`). Previously they were guarded only by `authenticateOAuth2Async`,
  i.e. by "is signed in at all" — and the Keycloak client backing the admin portal is a public
  client with the standard browser flow, so any account in the realm could obtain a token for
  it. Only one route (the raw-data delete) checked the role.

  This service is an operations tool, not a tenant-facing API. A single directive
  (`RoleDirectives.requireSuperuser`) now gates every route, and it answers 403 with
  `{"error":"superuser role required"}` rather than a bare status, so the caller can tell what
  is missing.

  No separate tenant check was added, deliberately: the master-data routes take the tenant from
  the request body, which is exactly the cross-tenant capability the superuser role is meant to
  have. With the role enforced, no other caller reaches them.

  **Operational note:** EEG registration lives under the `eeg` prefix and is therefore covered
  too. Whoever onboards new communities needs the `superuser` role, and after assigning it, a
  fresh sign-in — roles are frozen into the token when it is issued.

## [1.0.2] – 2026-09-07

### Changed
- CI: Preview-Deployments (ADR-0007) — Push auf `preview/**` baut+deployt on-demand in die Dev-Zone (sha-pinned, kein `:latest`), Auto-Reset bei Branch-Delete.

## [1.0.1] – 2026-06-30

### Changed
- CI: Snyk Code (SAST) workflow + SARIF upload to code scanning. (#10, #11)
- Docs: add and translate CHANGELOG. (#8, #9)

## [1.0.0] – 2026-06-28

First production release built entirely from public source.

### Fixed
- Auth: the JWKS fetch URL can be configured separately from the issuer. (#7)
- Dependencies: pinned `pekko-discovery` to 1.2.1 (fixes a mixed-versioning crash). (#5)

### Changed
- Migrated from Akka to Apache Pekko (resolves the BSL license block). (#2)
- CI: push to the registry's development tier with an auto-rollout bridge
  (dispatch-deploy). (#4)
- Added AGPL-3.0 license; README with service overview and tech stack. (#3, #6)
