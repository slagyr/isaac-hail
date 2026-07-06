Feature: Hail delivery
  The hail delivery worker ticks on the shared scheduler, reads routed
  delivery hails from hail/deliveries/ (each named by its own hail id),
  binds unbound (reach-one) deliveries to an idle candidate, and gates on
  session in-flight + crew capacity. For each ready delivery it claims the
  session — the bridge records a durable turn marker (isaac-7li9) and the
  worker deletes the delivery file — then schedules the turn as a background
  task WITHOUT waiting, so it never dispatches two turns on the same session
  at once. The turn opens with an origin+autonomy system preamble (this turn
  came from a hail; it runs unattended, the user may not see the reply or be
  available for questions) followed by the resolved prompt. On turn
  completion the delivery hail moves to hail/delivered/; a failed turn
  increments attempts and backs off (re-queued to deliveries/),
  dead-lettering to hail/failed/ after the 5-attempt max. A reach-all
  child delivery is just a delivery hail (carrying :source-hail); the
  worker treats it like any other and never touches the broadcast parent.

  In tests the scheduler interval is mocked away — ticks are invoked
  directly — and turn completion is driven explicitly with
  "the turn ends on session ...". A "wait | true" queued response holds a
  turn open so the in-flight window is observable.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a bound delivery dispatches a turn and moves to delivered
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value                  |
      | id       | hail-1                 |
      | params   | {:dilithium-leak true} |
      | prompt   | Seal the leak.         |
      | crew     | bartholomew            |
      | bound-session | :engine-room         |
      | attempts | 0                      |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content | #comment                        |
      | message | user         | Seal the leak.  | resolved prompt                 |
      | message | assistant    | Sealing now.    | grover's reply — turn completed |

  Scenario: an unbound delivery binds the idle candidate over the in-flight one
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew     |
      | bridge      | atticus  |
      | first-watch | cordelia |
    And session "first-watch" is in flight
    And the following model responses are queued:
      | type | content      | model  |
      | text | Bridge here. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path       | value                                                                       |
      | id         | hail-1                                                                      |
      | prompt     | Status report?                                                              |
      | candidates | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] |
      | attempts   | 0                                                                           |
    When the hail delivery worker ticks
    And the turn ends on session "bridge"
    Then session "bridge" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status report?  |
      | message | assistant    | Bridge here.    |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path    | value   | #comment                           |
      | crew    | atticus | bound to the idle candidate        |
      | bound-session | :bridge | first-watch was in flight, skipped |

  Scenario: a delivery to an in-flight session is left pending
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                         |
      | model         | grover |                                  |
      | max-in-flight | 2      | capacity is not the blocker here |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And session "engine-room" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value          |
      | id       | hail-1         |
      | prompt   | Seal the leak. |
      | crew     | bartholomew    |
      | bound-session | :engine-room |
      | attempts | 0              |
    When the hail delivery worker ticks
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                              |
      | id       | hail-1 | still pending — session was in flight |
      | attempts | 0      | gating is not a failed attempt        |
    And the isaac file "hail/delivered/hail-1.edn" does not exist

  Scenario: a delivery for an at-capacity crew is left pending
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                         |
      | model         | grover |                                  |
      | max-in-flight | 1      | one turn at a time for this crew |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
      | warp-core   | bartholomew |
    And session "warp-core" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value           |
      | id       | hail-1          |
      | prompt   | Check the core. |
      | crew     | bartholomew     |
      | bound-session | :engine-room  |
      | attempts | 0               |
    When the hail delivery worker ticks
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                                |
      | id       | hail-1 | still pending — bartholomew at capacity |
      | attempts | 0      |                                         |
    And the isaac file "hail/delivered/hail-1.edn" does not exist

  Scenario: the worker dispatches at most one turn per session, serializing across ticks
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                      |
      | model         | grover |                               |
      | max-in-flight | 2      | crew capacity is not the gate |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content        | model  | wait |
      | text | Leak sealed.   | grover | true |
      | text | Plasma vented. | grover |      |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value          |
      | id       | hail-1         |
      | prompt   | Seal the leak. |
      | crew     | bartholomew    |
      | bound-session | :engine-room |
      | attempts | 0              |
    And the isaac EDN file hail/deliveries/hail-2.edn exists with:
      | path     | value            |
      | id       | hail-2           |
      | prompt   | Vent the plasma. |
      | crew     | bartholomew      |
      | bound-session | :engine-room |
      | attempts | 0                |
    When the hail delivery worker ticks
    Then session "engine-room" in-flight status is true
    And the isaac file "hail/deliveries/hail-2.edn" EDN contains:
      | path | value  | #comment                                  |
      | id   | hail-2 | not dispatched — engine-room already busy |
    When the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-2.edn" EDN contains:
      | path | value  |
      | id   | hail-2 |

  Scenario: a dispatch failure increments attempts and backs off
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
      | path     | value          |
      | id       | hail-1         |
      | prompt   | Seal the leak. |
      | crew     | bartholomew    |
      | bound-session | :engine-room |
      | attempts | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path            | value                | #comment                              |
      | attempts        | 1                    | incremented after the failed dispatch |
      | next-attempt-at | 2026-04-21T10:00:01Z | tick time + 1s (first backoff step)   |
    And the isaac file "hail/delivered/hail-1.edn" does not exist
    And the isaac file "hail/failed/hail-1.edn" does not exist

  Scenario: a delivery that exhausts max attempts dead-letters to failed
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
      | path     | value          | #comment                                          |
      | id       | hail-1         |                                                   |
      | prompt   | Seal the leak. |                                                   |
      | crew     | bartholomew    |                                                   |
      | bound-session | :engine-room |                                                   |
      | attempts | 4              | one short of the 5-attempt max; this tick is last |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/failed/hail-1.edn" EDN contains:
      | path     | value  | #comment                                |
      | id       | hail-1 |                                         |
      | attempts | 5      | hit the max on this tick; dead-lettered |
    And the log has entries matching:
      | level | event               | id     | reason     |
      | error | :hail/dead-lettered | hail-1 | :exhausted |
    And the isaac file "hail/delivered/hail-1.edn" does not exist

  Scenario: a reach-all child delivery completes independently and leaves the broadcast parent untouched
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name   | crew    |
      | bridge | atticus |
    And the following model responses are queued:
      | type | content     | model  |
      | text | Bridge aye. | grover |
    And the isaac EDN file hail/broadcasts/hail-1.edn exists with:
      | path     | value           |
      | id       | hail-1          |
      | children | [hail-2 hail-3] |
    And the isaac EDN file hail/deliveries/hail-2.edn exists with:
      | path        | value      |
      | id          | hail-2     |
      | source-hail | hail-1     |
      | prompt      | Red alert! |
      | crew        | atticus    |
      | bound-session | :bridge   |
      | attempts    | 0          |
    When the hail delivery worker ticks
    And the turn ends on session "bridge"
    Then the isaac file "hail/delivered/hail-2.edn" EDN contains:
      | path        | value  |
      | id          | hail-2 |
      | source-hail | hail-1 |
    And the isaac file "hail/broadcasts/hail-1.edn" EDN contains:
      | path     | value           | #comment                        |
      | id       | hail-1          | parent untouched by the worker  |
      | children | [hail-2 hail-3] | no aggregation, list unchanged  |

  Scenario: the hail delivery worker tick is registered with the shared scheduler
    When the Isaac system is started
    Then the scheduled tasks include:
      | id           | trigger.kind | trigger.ms |
      | hail/deliver | interval     | 1000       |

  # --- Conform :frequencies onto the shared session selector (isaac-c58s) ---
  # A :with-* override in the flat :frequencies map projects to behavioral-keys
  # and applies to the dispatched turn, exactly like the prompt command.

  Scenario: --with-model overrides the model on the dispatched turn
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value    |
      | model          | echo-alt |
      | provider       | grover   |
      | context-window | 16384    |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | coil-work | bartholomew |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | On it.  |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                   | value               |
      | id                     | hail-1              |
      | crew                   | bartholomew         |
      | bound-session          | coil-work           |
      | frequencies.with-model | grover2             |
      | prompt                 | Resonance climbing. |
      | attempts               | 0                   |
    When the hail delivery worker ticks
    And the turn ends on session "coil-work"
    Then session "coil-work" has transcript matching:
      | type    | message.role | message.model | message.content     |
      | message | user         |               | Resonance climbing. |
      | message | assistant    | echo-alt      | On it.              |

  Scenario: a turn that dies on empty responses fails the delivery instead of completing it (isaac-k4mf)
    An empty-terminal-response turn failure is a DELIVERY failure: attempts
    increment and the hail backs off for redelivery (dead-lettering to
    hail/failed/ after max attempts, per the existing convention) — the
    session is never silently freed with the work unfinished.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value              |
      | id       | hail-1             |
      | bound-session | :engine-room     |
      | crew     | bartholomew        |
      | prompt   | Seal the leak.     |
      | attempts | 0                  |
    And the following model responses are queued:
      | type | content | model  |
      | text |         | grover |
      | text |         | grover |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  |
      | id       | hail-1 |
      | attempts | 1      |

  Scenario: a hail's lifecycle is fully reconstructable from the log (isaac-jnkp)
    Every state transition logs an INFO :hail/* event — grep :hail/ in the
    server log reconstructs any hail's journey chronologically. File state
    stays the durable ledger; the log is the chronological audit trail.
    Given default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Seal the leak.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When the config is loaded
    And isaac is run with "hail send --band engineering-intercom"
    And the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event           | session     |
      | :info | :hail/routed    | engine-room |
      | :info | :hail/bound     | engine-room |
      | :info | :hail/delivered | engine-room |

  Scenario: a failed delivery turn logs the attempt and backoff (isaac-jnkp)
    Given default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value          |
      | id       | hail-1         |
      | bound-session | :engine-room |
      | crew     | bartholomew    |
      | prompt   | Seal the leak. |
      | attempts | 0              |
    And the following model responses are queued:
      | type  | content   | model  |
      | error | boom      | grover |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event                | attempts |
      | :warn | :hail/attempt-failed | 1        |

  Scenario: a thrown delivery turn logs ex-class and ex-message on attempt-failed (isaac-cehc)
    Given default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | bound-session | :engine-room   |
      | crew          | bartholomew    |
      | prompt        | Seal the leak. |
      | attempts      | 0              |
    And the following model responses are queued:
      | type | content | model  |
      | text | unused  | grover |
    And a delivery whose turn throws with message "boom-xyz"
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event                | error      | ex-class                  | ex-message | attempts |
      | :warn | :hail/attempt-failed | :exception | clojure.lang.ExceptionInfo | boom-xyz   | 1        |

  @wip
  Scenario: a thrown delivery dead-letters with ex-class and ex-message (isaac-cehc, isaac-3tvq)
    Given default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content | model  |
      | text | unused  | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | bound-session | :engine-room   |
      | crew          | bartholomew    |
      | prompt        | Seal the leak. |
      | attempts      | 4              |
    And a delivery whose turn throws with message "boom-xyz"
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/failed/hail-1.edn" EDN contains:
      | path       | value      | #comment                          |
      | attempts   | 5          |                                   |
      | error      | :exception | failure class on the record       |
      | ex-message | boom-xyz   | diagnosis without log archaeology |
    And the log has entries matching:
      | level | event               | error      | ex-class                  | ex-message | reason     |
      | error | :hail/dead-lettered | :exception | clojure.lang.ExceptionInfo | boom-xyz   | :exhausted |

  Scenario: binding stamps bound-session on the delivery record (isaac-fq9c)
    A delivery's resolved target is :bound-session — distinct from addressing,
    which always lives under :frequencies. A record reader can never confuse
    "who was asked" with "who was chosen". (Existing fixtures/assertions using
    the old :session field are renamed in place with the implementation — see
    the bean's migration map.)
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew     |
      | bridge      | atticus  |
      | first-watch | cordelia |
    And session "first-watch" is in flight
    And the following model responses are queued:
      | type | content      | model  |
      | text | Bridge here. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path       | value                                                                       |
      | id         | hail-1                                                                      |
      | prompt     | Status report?                                                              |
      | candidates | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] |
      | attempts   | 0                                                                           |
    When the hail delivery worker ticks
    And the turn ends on session "bridge"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path          | value  |
      | bound-session | :bridge |

  @wip
  Scenario: an unavailable provider defers the delivery without burning attempts (isaac-3tvq)
    A provider wall (usage limit, quota, credit exhaustion) is weather, not
    poison — the drive classifies it as unavailable with a retry-after, and
    the worker defers the delivery the way it treats a busy session: back to
    pending, attempts untouched, no dead-letter progress.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type        | retry-after-ms | model  |
      | unavailable | 60000          | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 2              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path            | value                | #comment                                     |
      | attempts        | 2                    | unchanged — a wall is not evidence of poison |
      | next-attempt-at | 2026-04-21T10:01:00Z | tick time + retry-after-ms, not backoff      |
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event                   | session     | retry-after-ms |
      | :warn | :hail/delivery-deferred | engine-room | 60000          |

  @wip
  Scenario: the inflight recovery window defaults to 2h — live-length turns are not stolen (isaac-3tvq)
    The age-only orphan heuristic cannot tell a live turn from a crashed one;
    a 5-minute default stole every real work turn (cleared its in-flight
    guard, burned attempts as :worker-crash, enabled double-drive). 2h makes
    theft unrealistic; crash recovery just takes longer. Boundary pinned
    with one delivery on each side of the window.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/inflight/hail-recent.edn exists with:
      | path          | value                |
      | id            | hail-recent          |
      | prompt        | Seal the leak.       |
      | crew          | bartholomew          |
      | bound-session | :engine-room         |
      | attempts      | 0                    |
      | claimed-at    | 2026-04-21T08:01:00Z |
    And the isaac EDN file hail/inflight/hail-stale.edn exists with:
      | path          | value                |
      | id            | hail-stale           |
      | prompt        | Check the hull.      |
      | crew          | bartholomew          |
      | bound-session | :engine-room         |
      | attempts      | 0                    |
      | claimed-at    | 2026-04-21T07:59:00Z |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/inflight/hail-recent.edn" EDN contains:
      | path     | value | #comment                                 |
      | attempts | 0     | 1h59m old — inside the window, untouched |
    And the isaac file "hail/inflight/hail-stale.edn" does not exist
    And the isaac file "hail/deliveries/hail-stale.edn" EDN contains:
      | path     | value | #comment                        |
      | attempts | 1     | 2h01m old — recovered as before |

  Scenario: a suspended hail turn leaves its marker for resume — no reschedule, no attempts (isaac-2xj5)
    Suspend is not a failure: the delivery lives on only inside the stamped
    turn marker (embedded attempts intact) for isaac-vdfc to re-queue at next
    startup. It is neither re-queued, concluded, nor punished here.
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
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And in-flight turns are suspended
    Then a turn marker exists for session "engine-room" with:
      | key         | value  | #comment                             |
      | source      | :hail  |                                      |
      | delivery-id | hail-1 |                                      |
      | attempts    | 2      | unchanged — suspend is not a failure |
      | suspended   | true   |                                      |
      | boundary    | :clean |                                      |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/delivered/hail-1.edn" does not exist
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event                    | session     |
      | :info | :hail/delivery-suspended | engine-room |
