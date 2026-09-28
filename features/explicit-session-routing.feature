Feature: Explicit session id trumps band session selectors
  When a hail names :frequencies {:session ...}, that session id is the
  complete recipient coordinate. Band session-tags must not filter it out;
  band with-* overrides and prompt templates still apply. Send checks the
  address synchronously: an explicit session that does not exist is
  refused, nothing queued.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: An explicit session routes despite band session-tags the session lacks
    Given the isaac EDN file "config/hail/ci-failure.edn" exists with:
      | path         | value             |
      | session-tags | #{:orchestration} |
    And the isaac file "config/hail/ci-failure.md" exists with:
      """
      CI failure on the Marigold.
      """
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name                 | crew | tags |
      | glimmering-cardinal  | main | #{}  |
    When isaac is run with "hail send --band ci-failure --session glimmering-cardinal"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path    | value                |
      | id      | #turn-id             |
      | session | :glimmering-cardinal |
      | input   | CI failure on the Marigold. |

  @wip
  Scenario: Band with-crew still applies when the hail names an explicit session
    Given the isaac EDN file "config/hail/gauge-check.edn" exists with:
      | path         | value     |
      | session-tags | #{:wip}   |
      | with-crew    | navigator |
    And the isaac file "config/hail/gauge-check.md" exists with:
      """
      Gauge check.
      """
    And the isaac EDN file "config/crew/navigator.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew |
      | engine-room | main |
    When isaac is run with "hail send --band gauge-check --session engine-room"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                   | value        |
      | id                     | #turn-id     |
      | session                | :engine-room |
      | frequencies.with-crew  | "navigator"  |

  @wip
  Scenario: A missing explicit session does not trigger band create :if-missing
    Given the isaac EDN file "config/hail/spawn-band.edn" exists with:
      | path         | value       |
      | session-tags | #{:wip}     |
      | create       | :if-missing |
    And the isaac file "config/hail/spawn-band.md" exists with:
      """
      Spawn check.
      """
    When isaac is run with "hail send --band spawn-band --session missing-room"
    Then the stderr contains "no session: missing-room"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty
