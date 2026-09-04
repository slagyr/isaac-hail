Feature: Hail deferral when session context is exhausted (isaac-dark)
  Context-exhausted turns defer with zero attempt burn and post throttled
  attention to the comm outbox. A provider 400 whose message is a hard
  prompt/context overflow classifies as the same weather — hail must not
  retry it as :api-error. (isaac-bs5b)

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
  Scenario: Hail defers an unavailable turn without posting context-exhausted attention
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | context-window | 100   |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
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
    And the directory "comm/delivery/pending" has exactly 0 files

  @wip
  Scenario: a provider 400 for prompt length defers the hail without burning attempts
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type       | status | message                                                     | model  |
      | http-error | 400    | maximum prompt length is 128000 but request contains 130000 | grover |
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
      | path            | value                | #comment                                     |
      | attempts        | 0                    | unchanged — overflow is weather, not poison  |
      | next-attempt-at | 2026-04-21T10:05:00Z | tick + auth-tier 300000ms, not error backoff |
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event                   | session     | reason              | retry-after-ms |
      | :warn | :hail/delivery-deferred | engine-room | :context-exhausted | 300000         |
