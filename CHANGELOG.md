# Changelog

## Unreleased

### Fixed

- Hail pending/delivery paths honor the CLI `--root` binding when nexus `:root` is unset (feature in-process runs).
- Ship `isaac-manifest.edn` under `src/` so scratch feature classpaths that omit `resources/` still register hail CLI and band schema.

### Breaking

- Hail routing resolves recipients by `:session` and `:session-tags` only. `:crew-tags` and crew-based session matching are removed.
- Band `:crew` is now a single processing-crew id (not a seq selector). `:crew-tags` is retired and hard-rejects at config validate.
- Hail processing crew override lives at the hail top-level (`:crew`), not in `:frequency`. `hail send --crew` sets that field; `--crew-tag` is removed.
- Spawn deliveries carry the resolved processing crew from the router (`:no-host` undeliverable reason removed).
- Hail band and frequency config key `:spawn` renamed to `:spawn-session`. The old key is no longer read; update band declarations and hail frequency maps accordingly. The internal delivery action keyword `:spawn` is unchanged.