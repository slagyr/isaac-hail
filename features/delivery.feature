Feature: Hail-dispatched turn overrides
  A hail's :frequencies may carry :with-* override keys (with-crew,
  with-model, ...) and a band may declare a :cycle map. Both project onto
  the dispatched turn exactly like the prompt command's overrides do —
  the model actually used, and the cycle limits actually enforced, come
  from the band, not just the crew default. Everything else about how a
  turn plays out once queued (weather, suspension, cancellation, retries)
  is Agent's turn queue's business, not Hail's.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: --with-model overrides the model on the dispatched turn
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value    |
      | model          | echo-alt |
      | provider       | grover   |
      | context-window | 16384    |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/hail/engine-band.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | with-model   | grover2               |
    And the isaac file "config/hail/engine-band.md" exists with:
      """
      Resonance climbing.
      """
    And the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | On it.  |
    When isaac is run with "hail send --band engine-band"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                    | value     |
      | id                      | #turn-id  |
      | frequencies.with-model  | "grover2" |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "coil-work" has transcript matching:
      | type    | message.role | message.model | message.content     |
      | message | user         |               | Resonance climbing. |
      | message | assistant    | echo-alt      | On it.              |

  Scenario: a band's cycle.limit overrides the crew's on the dispatched turn (isaac-ntt6, isaac-9azm)
    The band's cycle map rides the dispatched turn, as before; what the
    turn queue does at the limit (wrap-up, continuation) is no longer
    hail's concern.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 5      |
    And the crew "bartholomew" allows tools: "exec/run"
    And the isaac EDN file "config/hail/engine-band.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | cycle.limit  | 1                     |
    And the isaac file "config/hail/engine-band.md" exists with:
      """
      Seal the leak.
      """
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments           | content                  |
      | exec__run | {"command": "true"} |                          |
      | exec__run | {"command": "true"} |                          |
      |           |                     | Checkpoint; next: valves |
    When isaac is run with "hail send --band engine-band"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path        | value    |
      | id          | #turn-id |
      | cycle.limit | 1        |
    When the turn queue ticks at "2026-04-21T10:00:00"
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event       | session     | ended-by     | cycle-limit |
      | :info | :turn/ended | engine-room | :cycle-limit | 1           |

  Scenario: a band's cycle map overrides the crew on the charge (isaac-tic5)
    The crew sets no checkpoint; the band does. The dispatched turn carries
    the band's :cycle map over the crew's, the same path the cycle limit
    already takes, so the turn queue nudges a checkpoint after the first
    cycle.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 10     |
    And the crew "bartholomew" allows tools: "exec/run"
    And the isaac EDN file "config/hail/engine-band.edn" exists with:
      | path                   | value                 |
      | session-tags           | #{:project/warp-coil} |
      | cycle.checkpoint-every | 1                     |
    And the isaac file "config/hail/engine-band.md" exists with:
      """
      Seal the leak.
      """
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments           | content |
      | exec__run | {"command": "true"} |         |
      |           |                     | done    |
    When isaac is run with "hail send --band engine-band"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                   | value    |
      | id                     | #turn-id |
      | cycle.checkpoint-every | 1        |
    When the turn queue ticks at "2026-04-21T10:00:00"
    And the turn ends on session "engine-room"
    Then the last LLM request matches:
      | key                  | value                                        |
      | messages[-1].content | contains "Checkpoint: save work in progress" |
    And the log has entries matching:
      | event                   | session     | cycle |
      | :turn/checkpoint-nudged | engine-room | 1     |
