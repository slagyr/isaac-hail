Feature: Hail deferral when session context is exhausted (isaac-dark)
  Context-exhausted turns defer with zero attempt burn and post throttled
  attention to the comm outbox.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: context-exhausted deferral does not increment hail attempts
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | context-window | 100   |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        | compaction-disabled |
      | engine-room | bartholomew | true                |
    And session "engine-room" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the following model responses are queued:
      | type        | retry-after-ms | model  | reason            |
      | unavailable | 300000         | grover | context-exhausted |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value |
      | attempts | 0     |
    And the log has entries matching:
      | level | event                   | session     | reason              | retry-after-ms |
      | :warn | :hail/delivery-deferred | engine-room | :context-exhausted | 300000         |

  @wip
  Scenario: context-exhausted deferral attention is throttled per session
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value      |
      | context-window | 100        |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        | compaction-disabled |
      | engine-room | bartholomew | true                |
    And session "engine-room" has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | block A oldest: planning notes about logging, tools, and the dispatch loop    |
      | message | assistant    | reply A: we agreed on output sinks, the compaction trigger, and tool dispatch |
      | message | user         | block B: more notes on retry behavior and the backoff between dispatch tries  |
      | message | assistant    | reply B: dispatcher retry is now idempotent with backoff between attempts     |
      | message | user         | latest question about what was finally decided across all of the above        |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | First defer.   |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                        |
      | comm    | discord                      |
      | target  | boiler-room                  |
      | content | contains "Context exhausted" |
