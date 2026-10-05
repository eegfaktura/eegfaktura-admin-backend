# Changelog

All notable changes to **eegfaktura-admin-backend (Scala/Pekko admin API)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

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
