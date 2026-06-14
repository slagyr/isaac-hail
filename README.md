# Isaac Hail

Hail dispatch for Isaac — bands, queue, router, delivery worker, HTTP
ingress, CLI, and the `hail-send` crew tool. Builtin module `:isaac.hail`
contributes `:hail` config schema, `isaac hail` CLI, `POST /hail/send`,
and the `hail-send` tool berth.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation)
(scheduler, nexus, fs) and [isaac-agent](https://github.com/slagyr/isaac-agent)
(bridge, charge, sessions, tool framework).

## Layout

- `src/isaac/hail/` — queue, router, delivery, bands, HTTP, CLI
- `src/isaac/tool/hail.clj` — `hail-send` LLM tool
- `resources/isaac-manifest.edn` — builtin manifest
- `spec/isaac/hail/` and `spec/isaac/tool/` — unit specs

Integration features (`features/hail/`) live in **isaac-server** — they
exercise the full host stack and avoid a circular dep with this repo.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-hail/       # this repo
```

```sh
bb spec    # speclj unit specs
bb ci      # same
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-hail {:local/root "../isaac-hail"}
;; or {:git/url "https://github.com/slagyr/isaac-hail.git" :git/sha "..."}
```