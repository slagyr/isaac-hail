Feature: Hail send
  `isaac hail send [addressing flags] [--params <edn>]` expands the band
  (template, params, data, metadata preamble), checks the address, and
  submits ONE turn to Agent's durable queue. Hail keeps no record of its
  own; the turn id it prints is Agent's. This bean covers the substrate
  (`hail.queue/send!` library function) and the `isaac hail send` CLI
  surface. v1 supports `--band` addressing only; other addressing flags
  (`--crew`, `--session`, `--crew-tag`, `--session-tag`) are follow-up.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                |
      | session-tags | #{:project/galley}  |
    And the isaac file "config/hail/bean-pickup.md" exists with:
      """
      Pick up the beans.
      """
    And the isaac EDN file "config/crew/wormwood.edn" exists with:
      | path  | value                |
      | model | grover               |
      | tags  | #{:project/galley}  |
    And the following sessions exist:
      | name       | crew        | tags                 |
      | galley | wormwood | #{:project/galley}  |

  @wip
  Scenario: isaac hail send submits a turn to Agent's queue
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value                |
      | id                       | #turn-id              |
      | frequencies.session-tags | #{:project/galley}  |
      | origin.params            | {:n 1}                |
      | origin.from              | :cli                  |

  @wip
  Scenario: each isaac hail send returns a distinct turn id
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":first-id |
    When isaac is run with "hail send --band bean-pickup --params '{:n 2}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":second-id |
    When isaac is run with "turns show #first-id"
    Then the stdout matches:
      | #"(?s).*:n 1.*" |

  @wip
  Scenario: the submitted turn carries a created-at timestamp
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path       | value                 |
      | id         | #turn-id              |
      | created-at | 2026-05-23T12:00:00Z  |

  @wip
  Scenario: isaac hail send prints the turn id to stdout
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}'"
    Then the stdout matches:
      | #"[a-z0-9]+" |
    And the exit code is 0

  @wip
  Scenario: isaac hail send --json prints the turn id and what was submitted
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}' --json"
    Then the exit code is 0
    And the stdout JSON contains:
      | path             | value                  |
      | id               | #"[a-z0-9]+"           |
      | origin.band      | "bean-pickup"          |
      | params.n         | 1                      |
      | from             | "cli"                  |
      | created-at       | "2026-05-23T12:00:00Z" |

  @wip
  Scenario: isaac hail send --edn prints the turn id and what was submitted
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}' --edn"
    Then the exit code is 0
    And the stdout EDN contains:
      | path             | value                 |
      | id               | #"[a-z0-9]+"          |
      | origin.band      | "bean-pickup"         |
      | params           | {:n 1}                |
      | created-at       | 2026-05-23T12:00:00Z  |

  @wip
  Scenario: isaac hail send submits a turn without params
    When isaac is run with "hail send --band bean-pickup"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value          |
      | id               | #turn-id       |
      | origin.band      | "bean-pickup"  |
      | origin.from      | :cli           |

  @wip
  Scenario: isaac hail send accepts a whole hail record from stdin
    Given stdin is:
      """
      {:frequencies {:band "bean-pickup"} :params {:n 1}}
      """
    When isaac is run with "hail send -"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value          |
      | id               | #turn-id       |
      | origin.band      | "bean-pickup"  |
      | origin.params    | {:n 1}         |
      | origin.from      | :cli           |
