Feature: Delivery claim via durable turn markers
  Claiming a delivery is the bind: the bridge — the single writer of durable
  turn markers (sessions/turns/<session-id>.edn) for every turn source —
  records the marker, hail writes the delivered receipt to hail/delivered/,
  and only then does the worker delete hail/deliveries/<id>.edn (isaac-7li9,
  isaac-9azm). The marker carries the turn's routing (source, started-at)
  and nothing of hail's: no delivery id, no attempts, no embedded payload —
  the receipt is the record. A crash between receipt and delete leaves a
  duplicate (receipt + stray delivery), never a loss: on any tick, a
  delivery whose receipt already exists is stale — removed, logged, never
  re-dispatched. hail/inflight/ and its orphan recovery (isaac-0tf3) stay
  replaced by this mechanism.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |

  @wip
  Scenario: claiming a delivery records the turn marker, writes the receipt, and removes the delivery file (isaac-9azm)
    Given the following model responses are queued:
      | type | content      | model  | wait |
      | text | Sealing now. | grover | true |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 2              |
    When the hail delivery worker ticks
    Then a turn marker exists for session "engine-room" with:
      | key        | value |
      | source     | :hail |
      | started-at | #*    |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path     | value  |
      | id       | hail-1 |
      | attempts | 2      |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    When the turn ends on session "engine-room"
    Then no turn marker exists for session "engine-room"
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a hail-bound turn marker survives restart and resumes in its own session (isaac-9azm)
    Given session "engine-room" has transcript:
      | type    | message.role | message.content |
      | message | user         | Seal the leak.  |
    And the isaac EDN file "hail/delivered/hail-restart.edn" exists with:
      | path          | value          |
      | id            | hail-restart   |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path      | value  |
      | source    | :hail  |
      | suspended | true   |
      | boundary  | :clean |
    And the following model responses are queued:
      | type | content       | model  |
      | text | Resuming now. | grover |
    When interrupted turns are resumed at "2026-09-11T04:00:14Z"
    And the turn queue ticks at "2026-09-11T04:00:20Z"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Seal the leak.     |
      | message | user         | #".*interrupted.*" |
      | message | assistant    | Resuming now.      |
    And the isaac file "hail/delivered/hail-restart.edn" EDN contains:
      | path | value        |
      | id   | hail-restart |
    And the log has entries matching:
      | level | event                 | requeued |
      | :info | :resume/scan-complete | 1        |

  @wip
  Scenario: a stray delivery whose receipt already exists is removed, not re-dispatched (isaac-9azm)
    Given the isaac EDN file "hail/delivered/hail-1.edn" exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                           | value          |
      | id                             | hail-1         |
      | prompt                         | Seal the leak. |
      | crew                           | bartholomew    |
      | bound-session                  | :engine-room   |
      | attempts                       | 2              |
      | data.notification-comm.id      | :discord       |
      | data.notification-comm.channel | boiler-room    |
    When the hail delivery worker ticks
    Then the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And the log has entries matching:
      | level | event                        | session     |
      | warn  | :hail/stale-delivery-removed | engine-room |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value       |
      | comm    | :discord    |
      | target  | boiler-room |
