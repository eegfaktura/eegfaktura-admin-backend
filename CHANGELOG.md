# Changelog

All notable changes to **eegfaktura-admin-backend (Scala/Pekko admin API)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

### Added
- Ops route `POST /admin/energystore/rawdata/delete` to remove the raw energy data of a single
  metering point within a time range (`{tenant, ecId, meteringPoint, start, end, dryRun}`). Gated on
  the `superuser` realm role; forwards the operator's bearer + `tenant` header to energystore's
  `POST /eeg/v2/{ecId}/rawdata/delete` (via sttp), so energystore's superuser-aware middleware
  authorizes the cross-tenant call. `dryRun` previews the affected amount without writing. New config
  `app.energystore.url` (env `ENERGYSTORE_URL`).

### Fixed
- CI: preview-deploys were broken (ImagePullBackOff) — the sbt-native-packager build only pushes
  `:latest` + `:<version>`, but `dispatch-preview-deploy` pins `:sha-<short>`, which never existed.
  The build now also tags the freshly built image with `sha-<short>` (via `docker buildx imagetools
  create`) so the pinned preview-deploy resolves. (Known remaining follow-up: preview builds still
  push `:latest` — sbt `dockerAliases` — which can transiently pollute the tag the default deploy uses.)

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
