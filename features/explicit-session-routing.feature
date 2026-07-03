Feature: Explicit session id trumps band session selectors
  When a hail names :frequencies {:session ...}, that session id is the complete
  recipient coordinate. Band session selectors must not filter it out; band
  with-* overrides and prompt templates still apply.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: An explicit session routes despite band session-tags the session lacks
    Given the isaac EDN file "config/hail/ci-failure.edn" exists with:
      | path         | value               |
      | session-tags | #{:orchestration}   |
      | reach        | :one                |
    And the isaac file "config/hail/ci-failure.md" exists with:
      """
      CI failure on the Marigold.
      """
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name               | crew | tags |
      | glimmering-cardinal | main | #{}  |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                | value                                              |
      | id                  | hail-1                                             |
      | frequencies.band    | ci-failure                                         |
      | frequencies.session | [:glimmering-cardinal]                            |
      | from                | :cli                                               |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value               |
      | bound-session | glimmering-cardinal |
      | crew    | main                |

  Scenario: An explicit session prevents band reach :all fan-out
    Given the isaac EDN file "config/hail/alert.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/command}  |
      | reach        | :all              |
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
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                | value                   |
      | id                  | hail-1                  |
      | frequencies.band    | alert                   |
      | frequencies.session | [:bridge]               |
      | from                | :cli                    |
    When the hail router ticks
    Then the isaac file "hail/broadcasts/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value  |
      | bound-session | bridge |

  Scenario: Band with-crew still applies when the hail names an explicit session
    Given the isaac EDN file "config/hail/gauge-check.edn" exists with:
      | path         | value      |
      | session-tags | #{:wip}    |
      | with-crew    | navigator  |
    And the isaac EDN file "config/crew/navigator.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew |
      | engine-room | main |
    When the config is loaded
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                | value              |
      | id                  | hail-1             |
      | frequencies.band    | gauge-check        |
      | frequencies.session | [:engine-room]     |
      | from                | :cli               |
    When the hail router ticks
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path | value     |
      | crew | navigator |

  Scenario: A missing explicit session does not trigger band create :if-missing
    Given the isaac EDN file "config/hail/spawn-band.edn" exists with:
      | path         | value        |
      | session-tags | #{:wip}      |
      | reach        | :one         |
      | create       | :if-missing  |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                | value            |
      | id                  | hail-1           |
      | frequencies.band    | spawn-band       |
      | frequencies.session | [:missing-room]  |
      | from                | :cli             |
    When the hail router ticks
    Then the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value          |
      | reason | :no-recipients |