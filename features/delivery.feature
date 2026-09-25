Feature: Hail delivery
  Hail is a mailman. The delivery worker ticks on the shared scheduler,
  reads routed delivery hails from hail/deliveries/ (each named by its own
  hail id), binds unbound (reach-one) deliveries to an idle candidate, and
  gates on the bound session's own in-flight turn — there is no crew-wide
  cap (isaac-ximd). For each ready delivery it
  starts a turn: the bridge records a durable turn marker (isaac-7li9), the
  receipt is written to hail/delivered/ and the deliveries/ file removed —
  at bind, before the turn ends (isaac-9azm). A delivery either started a
  turn or it did not; that is the only distinction hail draws. Only a
  delivery whose turn cannot start (an unresolved charge — unknown crew, no
  model — or a throw before the turn) is a delivery failure: attempts
  increment, it backs off (re-queued to deliveries/), and it dead-letters to
  hail/failed/ after the 5-attempt max. Everything that happens inside the
  turn — provider weather, suspension, cancellation, cycle limits, errors —
  is the drive's business (isaac-f3hq, isaac-xpkf, isaac-6doh); hail holds
  nothing open and never reads the turn's outcome. The turn opens with an
  origin+autonomy system preamble (this turn came from a hail; it runs
  unattended, the user may not see the reply or be available for questions)
  followed by the resolved prompt. A reach-all child delivery is just a
  delivery hail (carrying :source-hail); the worker treats it like any other
  and never touches the broadcast parent.

  In tests the scheduler interval is mocked away — ticks are invoked
  directly — and turn completion is driven explicitly with
  "the turn ends on session ...". A "wait | true" queued response holds a
  turn open so the in-flight window is observable.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: a bound delivery starts a turn and is delivered at bind, before the turn ends (isaac-9azm)
    Delivered means "a turn started". The receipt is written when the worker
    binds the turn, not when it ends: the record is never in limbo and hail
    holds nothing open for the turn's duration.
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
      | path          | value                  |
      | id            | hail-1                 |
      | params        | {:dilithium-leak true} |
      | prompt        | Seal the leak.         |
      | crew          | bartholomew            |
      | bound-session | :engine-room           |
      | attempts      | 0                      |
    When the hail delivery worker ticks
    Then session "engine-room" in-flight status is true
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  | #comment                                  |
      | id   | hail-1 | the receipt — written while the turn runs |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event           | session     |
      | :info | :hail/bound     | engine-room |
      | :info | :hail/delivered | engine-room |
    When the turn ends on session "engine-room"
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
      | path  | value  |
      | model | grover |
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

  Scenario: the worker dispatches at most one turn per session, serializing across ticks
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
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

  @wip
  Scenario: a delivery whose turn cannot start increments attempts and backs off (isaac-9azm)
    An unresolved charge — here an unknown crew — means no turn ever
    started. That, and only that, is a delivery failure: attempts++, backoff,
    back to deliveries/.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          | #comment                      |
      | id            | hail-1         |                               |
      | prompt        | Seal the leak. |                               |
      | crew          | nobody         | no such crew — cannot resolve |
      | bound-session | :engine-room   |                               |
      | attempts      | 0              |                               |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path            | value                | #comment                            |
      | attempts        | 1                    | incremented — no turn started       |
      | next-attempt-at | 2026-04-21T10:00:01Z | tick time + 1s (first backoff step) |
    And the isaac file "hail/delivered/hail-1.edn" does not exist
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event                | attempts | error         |
      | :warn | :hail/attempt-failed | 1        | :unknown-crew |

  @wip
  Scenario: a delivery whose turn cannot start exhausts max attempts and dead-letters to failed (isaac-9azm)
    The dead-letter budget is for poison that never becomes a turn.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          | #comment                                          |
      | id            | hail-1         |                                                   |
      | prompt        | Seal the leak. |                                                   |
      | crew          | nobody         | no such crew — cannot resolve                     |
      | bound-session | :engine-room   |                                                   |
      | attempts      | 4              | one short of the 5-attempt max; this tick is last |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/failed/hail-1.edn" EDN contains:
      | path     | value         | #comment                                |
      | id       | hail-1        |                                         |
      | attempts | 5             | hit the max on this tick; dead-lettered |
      | error    | :unknown-crew | why no turn ever started                |
    And the log has entries matching:
      | level | event               | id     | reason     | error         |
      | error | :hail/dead-lettered | hail-1 | :exhausted | :unknown-crew |
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

  @wip
  Scenario: a turn that goes silent is delivered; the drive parks it as weather (isaac-k4mf, isaac-9azm)
    A turn started, so the delivery is done. Silence after the nudge is the
    drive's weather (isaac-f3hq): the session's marker parks with :silence and
    the drive's sweep re-drives it — hail neither retries nor dead-letters,
    and the session is never silently freed with the work unfinished.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    And the following model responses are queued:
      | type | content | model  |
      | text |         | grover |
      | text |         | grover |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And a turn marker exists for session "engine-room" with:
      | key       | value    |
      | source    | :hail    |
      | suspended | true     |
      | reason    | :silence |

  Scenario: a successful delivery leaves the hail findable after the deliveries file is gone (isaac-u7ug)
    Hails never die as records. Claiming deletes hail/deliveries/<id>.edn so
    the worker will not re-dispatch, but the hail itself must remain
    findable — hail_get / find-by-id must resolve it after the turn ends.
    Observed 2026-08-23: send returned 1232eaed, the worker delivered the
    turn, then no file existed in any hail dir.
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
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path   | value          |
      | id     | hail-1         |
      | prompt | Seal the leak. |

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

  @wip
  Scenario: a delivery whose turn cannot start logs the attempt and backoff (isaac-jnkp, isaac-9azm)
    Given default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          | #comment                      |
      | id            | hail-1         |                               |
      | prompt        | Seal the leak. |                               |
      | crew          | nobody         | no such crew — cannot resolve |
      | bound-session | :engine-room   |                               |
      | attempts      | 0              |                               |
    When the hail delivery worker ticks
    Then the log has entries matching:
      | level | event                | attempts | error         |
      | :warn | :hail/attempt-failed | 1        | :unknown-crew |

  @wip
  Scenario: a throw before the turn starts logs ex-class and ex-message on attempt-failed (isaac-cehc, isaac-9azm)
    A throw while preparing the turn (rendering the band prompt, building
    the charge) is a delivery failure with a diagnosis; a throw inside the
    turn is the drive's and never reaches hail.
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
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    And a delivery whose turn cannot start throws with message "boom-xyz"
    When the hail delivery worker ticks
    Then the log has entries matching:
      | level | event                | error      | ex-class                   | ex-message | attempts |
      | :warn | :hail/attempt-failed | :exception | clojure.lang.ExceptionInfo | boom-xyz   | 1        |

  @wip
  Scenario: a throw before the turn starts dead-letters with ex-class and ex-message (isaac-cehc, isaac-3tvq, isaac-9azm)
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
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 4              |
    And a delivery whose turn cannot start throws with message "boom-xyz"
    When the hail delivery worker ticks
    Then the isaac file "hail/failed/hail-1.edn" EDN contains:
      | path       | value      | #comment                          |
      | attempts   | 5          |                                   |
      | error      | :exception | failure class on the record       |
      | ex-message | boom-xyz   | diagnosis without log archaeology |
    And the log has entries matching:
      | level | event               | error      | ex-class                   | ex-message | reason     |
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
  Scenario: a provider wall is the turn's weather — delivered at bind, parked by the drive, attempts untouched (isaac-3tvq, isaac-9azm)
    A wall (usage limit, quota, credit exhaustion) is weather, not poison —
    and it is the drive's weather. The turn started, so the delivery is
    delivered; the drive parks the session's marker with backoff and its
    sweep re-drives it (isaac-nqeq, isaac-f3hq). Hail's attempts never move
    because hail never sees the wall.
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
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path     | value  | #comment                                     |
      | id       | hail-1 |                                              |
      | attempts | 2      | unchanged — a wall is not evidence of poison |
    And a turn marker exists for session "engine-room" with:
      | key       | value                |
      | source    | :hail                |
      | suspended | true                 |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:01:00Z |
    And the isaac file "hail/failed/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event           | session     | reason | retry-at             |
      | :warn | :turn/suspended | engine-room | :wall  | 2026-04-21T10:01:00Z |

  @wip
  Scenario: a stalled provider stream is the turn's weather — delivered at bind, parked by the drive (isaac-6zk5, isaac-9azm)
    An SSE idle stall is provider weather, not poison — the drive parks it
    with the provider's retry-after and its sweep re-drives it.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type        | retry-after-ms | model  | reason         |
      | unavailable | 90000          | grover | stream-stalled |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 2              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path     | value  |
      | id       | hail-1 |
      | attempts | 2      |
    And a turn marker exists for session "engine-room" with:
      | key       | value                |
      | source    | :hail                |
      | suspended | true                 |
      | reason    | :stall               |
      | retry-at  | 2026-04-21T10:01:30Z |
    And the isaac file "hail/failed/hail-1.edn" does not exist

  @wip
  Scenario: auth weather parks the turn and the drive's sweep completes it when the provider recovers (isaac-5a4n, isaac-9azm)
    Hails never die: an expired login parks the turn, not the delivery. The
    receipt was written at bind; when auth is healthy again the weather sweep
    re-drives the turn in its own session and it completes. Nothing counts
    against the dead-letter budget.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type        | retry-after-ms | model  | reason | content |
      | unavailable | 300000         | grover | auth   |         |
      | text        |                | grover |        | Sealed. |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And a turn marker exists for session "engine-room" with:
      | key       | value                |
      | source    | :hail                |
      | suspended | true                 |
      | reason    | :auth                |
      | retry-at  | 2026-04-21T10:05:00Z |
    When the resume sweep runs at "2026-04-21T10:05:30Z"
    And the turn ends on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Seal the leak.  |
      | message | assistant    | Sealed.         |
    And no turn marker exists for session "engine-room"
    And the isaac file "hail/failed/hail-1.edn" does not exist

  @wip
  Scenario: a suspended hail turn leaves a plain marker for the drive to resume (isaac-2xj5, isaac-9azm)
    Suspend is the drive's business. The delivery was delivered at bind; the
    marker carries the turn's routing and the suspend stamp and nothing of
    hail's — no delivery payload, no attempts. The drive resumes it in its
    own session at the next start (isaac-6doh).
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
      | key       | value  |
      | source    | :hail  |
      | suspended | true   |
      | boundary  | :clean |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist

  @wip
  Scenario: a band's cycle.limit overrides the crew's on the dispatched turn (isaac-ntt6, isaac-9azm)
    The band's cycle map rides the charge, as before; what the drive does at
    the limit (wrap-up, continuation — isaac-xpkf) is no longer hail's.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 5      |
    And the crew "bartholomew" allows tools: "exec/run"
    And the isaac EDN file "config/hail/engine-band.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | cycle.limit   | 1                     |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments           | content                  |
      | exec__run | {"command": "true"} |                          |
      | exec__run | {"command": "true"} |                          |
      |           |                     | Checkpoint; next: valves |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | band          | engine-band    |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event       | session     | ended-by     | cycle-limit |
      | :info | :turn/ended | engine-room | :cycle-limit | 1           |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  | #comment                                          |
      | id   | hail-1 | delivered at bind — continuations are the drive's |

  @wip
  Scenario: a successful turn without outbound hail-send still delivers (isaac-fgo0, isaac-9azm)
    Hail is pure transport: bean-workflow convergence is prompt-driven. A quiet
    successful turn is delivered — the receipt was written at bind and hail
    never re-queues.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the built-in tools are registered
    And the following model responses are queued:
      | content |
      | Done.   |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value                    |
      | id            | hail-1                   |
      | prompt        | Hand off the bean.       |
      | crew          | bartholomew              |
      | bound-session | :engine-room             |
      | attempts      | 0                        |
      | params        | {:bean-id "isaac-limbo"} |
      | data          | {:bean-repo "isaac"}     |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" exists
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the log has entries matching:
      | level | event           | session     |
      | :info | :hail/delivered | engine-room |

  @wip
  Scenario: cancelling a live hail turn is the drive's cancel — the receipt stands (isaac-9azm)
    Hail is done once the turn started. Cancel ends the turn (the drive logs
    it); the delivered receipt written at bind is the delivery's whole story.
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
      | attempts      | 0              |
    When the hail delivery worker ticks
    Then session "engine-room" in-flight status is true
    When isaac is run with "sessions cancel engine-room"
    Then the exit code is 0
    When the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And the log has entries matching:
      | level | event       | session     | ended-by   |
      | :info | :turn/ended | engine-room | :cancelled |

  Scenario: a band's cycle map overrides the crew on the charge (isaac-tic5)
    The crew sets no checkpoint; the band does. The charge carries the band's
    :cycle map over the crew's, the same path the cycle limit already takes,
    so the drive nudges a checkpoint after the first cycle.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 10     |
    And the crew "bartholomew" allows tools: "exec/run"
    And the isaac EDN file "config/hail/engine-band.edn" exists with:
      | path                   | value                 |
      | session-tags           | #{:project/warp-coil} |
      | cycle.checkpoint-every | 1                     |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments           | content |
      | exec__run | {"command": "true"} |         |
      |           |                     | done    |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | band          | engine-band    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the last LLM request matches:
      | key                  | value                                        |
      | messages[-1].content | contains "Checkpoint: save work in progress" |
    And the log has entries matching:
      | event                   | session     | cycle |
      | :turn/checkpoint-nudged | engine-room | 1     |
    And the isaac file "hail/delivered/hail-1.edn" exists
