Feature: Delivery claim via durable turn markers
  Claiming a delivery no longer moves it to hail/inflight/ — the bridge is
  the single writer of durable turn markers (sessions/turns/<session-id>.edn)
  for every turn source. The worker hands the full delivery record to the
  bridge inside the charge; the bridge records the marker with the delivery
  payload EMBEDDED (attempts, backoff, claimed-at); only after the record
  returns does the worker delete hail/deliveries/<id>.edn. A crash between
  the two leaves a duplicate (marker + stray delivery), never a loss: on any
  tick, a delivery already referenced by a turn marker is stale — removed,
  logged, never re-dispatched. hail/inflight/ and its orphan recovery
  (isaac-0tf3) are replaced by this mechanism. (isaac-7li9)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: claiming a delivery records the turn marker and removes the delivery file
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
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
      | key         | value  |
      | source      | :hail  |
      | delivery-id | hail-1 |
      | attempts    | 2      |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    When the turn ends on session "engine-room"
    Then no turn marker exists for session "engine-room"
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a stray delivery already claimed by a turn marker is removed, not re-dispatched
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And a turn marker exists for session "engine-room" referencing delivery "hail-1"
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 2              |
    When the hail delivery worker ticks
    Then the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/delivered/hail-1.edn" does not exist
    And a turn marker exists for session "engine-room" with:
      | key         | value  |
      | delivery-id | hail-1 |
    And the log has entries matching:
      | level | event                        | session     |
      | warn  | :hail/stale-delivery-removed | engine-room |
