Feature: Bound deliveries never sit unclaimed silently (isaac-at5m)
  A bound delivery in hail/deliveries/ is either claimed on a tick or the
  tick says loudly why it was skipped. A bound delivery that stays unclaimed
  past a threshold is recovered — re-checked against the real session state,
  claimed if the session is actually idle, otherwise left bound — a stale
  bind is not requeued onto another session of the crew (isaac-ximd) — and
  an operator can drop it with `isaac hail drop <id>`. Observed 2026-08-29:
  hail 1164c784 sat bound to an idle isaac-work-1 at attempts 0 forever.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |

# isaac-udlg (2026-09-21, Micah): the skip is still logged on every tick with
# its reason — at5m's "never silently" contract is intact — but at :debug rather
# than :warn. Expected backpressure (:session-in-flight) ran
# 357 warns in one log file and buried real errors. :session-missing still warns.
# The crew-wide cap is gone (isaac-ximd); a busy crew is not a skip reason.

  Scenario: a gated bound delivery says why it is waiting once, not once per tick
    Given session "engine-room" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the hail delivery worker ticks at "2026-04-21T10:00:30Z"
    Then the log has exactly 1 entries matching:
      | level  | event                  | id     | session     | reason             | unclaimed-ms |
      | :debug | :hail/delivery-skipped | hail-1 | engine-room | :session-in-flight | #*           |
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value |
      | attempts | 0     |

  # No crew-wide cap (isaac-ximd): one busy session never gates another session's
  # delivery on the same crew; only the bound session's own turn does.

  @wip
  Scenario: a busy session on the crew does not gate another session's delivery — no crew-wide cap (isaac-ximd)
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | boiler-room | bartholomew |
      | engine-room | bartholomew |
    And session "boiler-room" is in flight
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Sealed. |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  Scenario: a bound delivery unclaimed past the stale threshold while its session is idle is claimed with a recovery log
    The 08-29 shape: the in-flight gate said busy, the session was idle. Past
    the threshold the worker re-derives in-flight from the session's turn
    marker instead of the in-memory gate, finds it idle, and claims.
    Given config:
      | hail-settings.stale-bound-ms | 300000 |
    And the in-flight gate falsely reports session "engine-room" busy
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value                |
      | id            | hail-1               |
      | prompt        | Seal the leak.       |
      | crew          | bartholomew          |
      | bound-session | :engine-room         |
      | bound-at      | 2026-04-21T10:00:00Z |
      | attempts      | 0                    |
    When the hail delivery worker ticks at "2026-04-21T10:01:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value |
      | attempts | 0     |
    When the hail delivery worker ticks at "2026-04-21T10:06:00Z"
    And the turn ends on session "engine-room"
    Then the log has entries matching:
      | level | event                    | id     | session     | reason              |
      | :warn | :hail/delivery-recovered | hail-1 | engine-room | :false-in-flight    |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a bound delivery unclaimed past the stale threshold while its session is genuinely busy stays bound — no crew-wide rebound (isaac-ximd)
    A stale bind used to requeue onto another idle session of the crew, because
    the crew cap made that session the only place the delivery could run. With
    no crew-wide cap the other session is not a substitute: the delivery stays
    bound to the busy session and is claimed when that session is idle.
    Given config:
      | hail-settings.stale-bound-ms | 300000 |
    And the following sessions exist:
      | name        | crew        |
      | boiler-room | bartholomew |
    And session "engine-room" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value                |
      | id            | hail-1               |
      | prompt        | Seal the leak.       |
      | crew          | bartholomew          |
      | bound-session | :engine-room         |
      | bound-at      | 2026-04-21T10:00:00Z |
      | attempts      | 0                    |
    When the hail delivery worker ticks at "2026-04-21T10:06:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path          | value        |
      | bound-session | :engine-room |
      | attempts      | 0            |
    And the isaac file "hail/delivered/hail-1.edn" does not exist
    And the log has no entries matching:
      | event                    | reason         |
      | :hail/delivery-recovered | :rebound-stale |

  Scenario: the bind timestamp is recorded when the worker binds a delivery
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path     | value          |
      | id       | hail-1         |
      | prompt   | Seal the leak. |
      | crew     | bartholomew    |
      | attempts | 0              |
    And session "engine-room" is in flight
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path          | value                |
      | bound-session | :engine-room         |
      | bound-at      | 2026-04-21T10:00:00Z |

  Scenario: an operator drops a bound-unclaimed delivery and nothing phantom gates the session
    Given the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When isaac is run with "hail drop hail-1"
    Then the exit code is 0
    And the stdout contains "hail-1"
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value    |
      | id     | hail-1   |
      | reason | :dropped |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the isaac EDN file hail/deliveries/hail-2.edn exists with:
      | path          | value          |
      | id            | hail-2         |
      | prompt        | Seal it again. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-2.edn" EDN contains:
      | path | value  |
      | id   | hail-2 |

  Scenario: hail drop of an unknown id fails cleanly
    When isaac is run with "hail drop nope99"
    Then the exit code is 1
    And the stderr contains "nope99"
