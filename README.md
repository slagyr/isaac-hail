# Isaac Hail

Hail queue, router, delivery worker, HTTP route, and `hail-send` crew tool.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Integration acceptance
tests live in `features/` and pull step definitions from agent + server sibling
spec trees (see `:features` in `deps.edn`).

```sh
bb spec       # unit specs
bb features   # acceptance features (50 scenarios)
bb ci         # unit specs only until delivery/router harness is green
```

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-server/
  isaac-hail/   # this repo
```