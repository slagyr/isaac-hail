# 🍏 Isaac Hail 📣

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-hail/main/isaac-hail.png" alt="isaac-hail" style="margin-right: 20px; margin-bottom: 10px;">

Hail queue, router, delivery worker, HTTP route, and `hail-send` crew tool for out-of-band interrupt delivery.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Integration acceptance
tests live in `features/` and pull step definitions from agent + server sibling
spec trees (see `:features` in `deps.edn`).

<br>

[![Hail](https://github.com/slagyr/isaac-hail/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-hail/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Hail queue, router, and delivery worker for out-of-band messages.
- HTTP route for receiving hails (`/hail`).
- `hail-send` crew tool for sending interrupts.
- Band resolution and attention mechanisms.
- Integration with sessions and comm delivery.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-server/
  isaac-hail/   # this repo
```

```sh
bb spec       # unit specs
bb features   # acceptance features (50 scenarios)
bb ci         # specs + features
```

From the JVM, compose `:test` with a runner alias (shared test deps live in `:test` only):

```sh
clj -M:test:spec       # speclj specs
clj -M:test:features   # gherclj acceptance tests
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-hail {:local/root "../isaac-hail"}
;; or {:git/url "https://github.com/slagyr/isaac-hail.git" :git/sha "..."}
```