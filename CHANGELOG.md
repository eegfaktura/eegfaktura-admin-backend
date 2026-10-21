# Changelog

All notable changes to **eegfaktura-admin-backend (Scala/Pekko admin API)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

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
