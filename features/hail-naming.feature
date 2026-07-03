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

  Scenario: sequential strategy mints reach-all child deliveries as hail-2 and hail-3
    Given config:
      | hail-settings.naming-strategy | sequential |
    And the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     | tags             |
      | bridge      | atticus  | #{:role/command} |
      | first-watch | cordelia | #{:role/command} |
    And the isaac EDN file "hail/pending/hail-1.edn" exists with:
      | path        | value                            |
      | id          | hail-1                           |
      | frequencies | {:session-tags #{:role/command}} |
      | reach       | :all                             |
      | prompt      | Red alert!                       |
      | from        | :cli                             |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/broadcasts/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And child delivery for session bridge EDN contains:
      | path        | value    |
      | id          | hail-2   |
      | source-hail | hail-1   |
      | crew        | :atticus |
      | bound-session | :bridge |
    And child delivery for session first-watch EDN contains:
      | path        | value        |
      | id          | hail-3       |
      | source-hail | hail-1       |
      | crew        | :cordelia    |
      | bound-session | :first-watch |
    And delivery hail count is 2

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
