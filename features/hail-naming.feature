Feature: Hail naming strategy
  Hail ids are minted from a configurable naming strategy. The default
  remains bare short-uuid, while installs may opt into full UUIDs or
  deterministic sequential ids for test-friendly behavior.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: sequential strategy resumes at the next hail number
    Given config:
      | hail-settings.naming-strategy | sequential |
    And the EDN isaac file "hail/delivered/hail-1.edn" exists with:
      | path      | value  |
      | id        | hail-1 |
      | thread-id | hail-1 |
    When isaac is run with "hail send --band bean-pickup --params '{:n 2}'"
    Then the exit code is 0
    And the stdout contains "hail-2"
    And the isaac file "hail/pending/hail-2.edn" EDN contains:
      | path        | value                 |
      | id          | hail-2                |
      | thread-id   | hail-2                |
      | frequencies | {:band "bean-pickup"} |
      | params      | {:n 2}                |
      | from        | :cli                  |

  Scenario: uuid strategy prints a full UUID hail id
    Given config:
      | hail-settings.naming-strategy | uuid |
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}' --json"
    Then the exit code is 0
    And the stdout JSON contains:
      | path             | value                                                              |
      | id               | #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$":hail-id |
      | thread-id        | #hail-id                                                           |
      | frequencies.band | "bean-pickup"                                                      |
      | params           | {"n": 1}                                                           |
      | from             | "cli"                                                              |

  Scenario: absent hail naming config defaults to a bare short-uuid
    When isaac is run with "hail send --band bean-pickup --params '{:n 1}' --json"
    Then the exit code is 0
    And the stdout JSON contains:
      | path             | value                  |
      | id               | #"^[0-9a-f]{8}$":hail-id |
      | thread-id        | #hail-id               |
      | frequencies.band | "bean-pickup"          |
      | params           | {"n": 1}               |
      | from             | "cli"                  |
