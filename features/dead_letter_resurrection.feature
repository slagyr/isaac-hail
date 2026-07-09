Feature: Dead-letter resurrection (isaac-jx7u)
  Dead-lettered hails post attention to the comm outbox and can be resurrected
  with `isaac hail requeue <id>`.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: dead-letter on attempt 5 posts comm attention from delivery notification-comm
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type  | content | model  |
      | error | boom    | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value                                                      |
      | id            | hail-1                                                     |
      | prompt        | Seal the leak.                                             |
      | crew          | bartholomew                                                |
      | bound-session | :engine-room                                               |
      | attempts      | 4                                                          |
      | params        | {:bean-id "isaac-zzz"}                                    |
      | data          | {:notification-comm {:id "discord" :channel "boiler-room"}} |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/failed/hail-1.edn" EDN contains:
      | path     | value      |
      | attempts | 5          |
      | error    | :llm-error |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                  |
      | comm    | discord                                |
      | target  | boiler-room                            |
      | content | contains "isaac-zzz" and "dead-letter" |

  Scenario: requeue resurrects and redelivers
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the isaac EDN file hail/failed/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 5              |
      | error         | :api-error     |
    When isaac is run with "hail requeue hail-1"
    Then the exit code is 0
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path           | value          |
      | id             | hail-1         |
      | attempts       | 0              |
      | prompt         | Seal the leak. |
      | requeued-error | :api-error     |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  Scenario: unknown id fails cleanly
    When isaac is run with "hail requeue nope99"
    Then the stderr contains "nope99"
    And the exit code is 1
