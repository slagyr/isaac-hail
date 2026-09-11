# Changelog

## 0.1.17

- Cancelled hail turns are archived, not delivered or re-queued (isaac-jejt); pins isaac-agent ac1bf9b (operator cancel stamp).

## Unreleased

### Fixed

- HTTP `POST /hail/send` no longer keywordizes a string `:session` (or `:session-tags`) character-wise. A string normalizes to the same vector/set the CLI produces for `--session` / `--session-tag`; a vector of strings maps element-wise; other shapes 400 naming the field. Same audit for `:crew`.
- Hail pending/delivery paths honor the CLI `--root` binding when nexus `:root` is unset (feature in-process runs).
- Ship `isaac-manifest.edn` under `src/` so scratch feature classpaths that omit `resources/` still register hail CLI and band schema.

### Breaking

- Hail routing resolves recipients by `:session` and `:session-tags` only. `:crew-tags` and crew-based session matching are removed.
- Band `:crew` is now a single processing-crew id (not a seq selector). `:crew-tags` is retired and hard-rejects at config validate.
- Hail processing crew override lives at the hail top-level (`:crew`), not in `:frequency`. `hail send --crew` sets that field; `--crew-tag` is removed.
- Spawn deliveries carry the resolved processing crew from the router (`:no-host` undeliverable reason removed).
- Hail band and frequency config key `:spawn` renamed to `:spawn-session`. The old key is no longer read; update band declarations and hail frequency maps accordingly. The internal delivery action keyword `:spawn` is unchanged.