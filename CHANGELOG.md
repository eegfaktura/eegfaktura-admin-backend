# Changelog

Alle nennenswerten Änderungen an **eegfaktura-admin-backend (Scala/Pekko Admin-API)** werden hier dokumentiert.

Das Format orientiert sich an [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
die Versionierung an den Deployment-Release-Tags. Detail-Diffs bleiben im `git log`;
dieser Changelog hebt die für Überblick und Betrieb relevanten Änderungen hervor.

## [Unreleased]

## [1.0.0] – 2026-06-28

Erster vollständig aus öffentlichem Quellcode gebauter Produktiv-Release.

### Fixed
- Auth: JWKS-Abruf-URL kann getrennt vom Issuer konfiguriert werden. (#7)
- Abhängigkeiten: `pekko-discovery` auf 1.2.1 gepinnt (behebt Mixed-Versioning-Crash). (#5)

### Changed
- Migration von Akka auf Apache Pekko (löst BSL-Lizenz-Blockade). (#2)
- CI: Push in den Development-Tier der Registry mit Auto-Rollout-Bridge
  (dispatch-deploy). (#4)
- AGPL-3.0-Lizenz ergänzt; README mit Service-Überblick und Tech-Stack. (#3, #6)
