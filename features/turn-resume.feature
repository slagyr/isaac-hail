Feature: Startup resume of interrupted turns
  On startup, the bridge scans the durable turn markers (isaac-7li9) and
  resumes what a restart interrupted. Hail-sourced markers are re-queued to
  hail/deliveries/ and delivered by the normal worker — no special resume
  path downstream. Pricing follows the evidence: a :suspended marker (clean
  shutdown, isaac-2xj5) re-queues with attempts UNCHANGED; an unstamped
  orphan (hard crash) pays attempts+1, because if a poison turn crashed the
  server the dead-letter budget is the only crash-loop breaker. Transcript
  repair runs before re-queue: dangling toolCalls get a synthesized result
  ("result unknown — verify before repeating") so the re-driven turn never
  presents the orphaned-tool-call shape providers reject (isaac-63f3 family).
  Resume order per marker is re-queue FIRST, delete marker SECOND — a crash
  between leaves both, which converges via the stale-delivery dedup guard.
  The resume scan runs before the delivery worker's first tick. This feature
  replaces the hail/inflight orphan recovery (isaac-0tf3). (isaac-vdfc)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: a suspended hail marker is re-queued at startup — attempts intact, then delivered
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path           | value                |
      | source         | :hail                |
      | delivery-id    | hail-1               |
      | prompt         | Seal the leak.       |
      | crew           | bartholomew          |
      | bound-session  | :engine-room         |
      | attempts       | 2                    |
      | suspended      | true                 |
      | boundary       | :clean               |
      | interrupted-at | 2026-04-21T09:59:00Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                        |
      | id       | hail-1 |                                 |
      | attempts | 2      | unchanged — suspend was planned |
    And no turn marker exists for session "engine-room"
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a hard-crash orphan is re-queued with attempts incremented — crash is evidence
    No :suspended stamp means suspend never ran: the process died hard. If a
    poison turn is what crashed the server, the dead-letter budget is the
    only crash-loop breaker.
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path          | value                |
      | source        | :hail                |
      | delivery-id   | hail-1               |
      | prompt        | Seal the leak.       |
      | crew          | bartholomew          |
      | bound-session | :engine-room         |
      | attempts      | 2                    |
      | started-at    | 2026-04-21T09:40:00Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                          |
      | id       | hail-1 |                                   |
      | attempts | 3      | crash costs one — loop protection |
    And no turn marker exists for session "engine-room"
    And the log has entries matching:
      | level | event                | session     |
      | :warn | :resume/crash-orphan | engine-room |

  @wip
  Scenario: a dangling toolCall is repaired with a synthesized result before resume
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And session "engine-room" has transcript:
      | type     | message.role | message.content | id   |
      | message  | user         | Seal the leak.  |      |
      | toolCall | assistant    |                 | tc-1 |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path          | value          |
      | source        | :hail          |
      | delivery-id   | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
      | attempts      | 0              |
      | suspended     | true           |
      | boundary      | :unclean       |
    And the following model responses are queued:
      | type | content     | model  |
      | text | Sealed now. | grover |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then session "engine-room" has transcript matching:
      | type       | message.content                                   | #comment                    |
      | message    | Seal the leak.                                    |                             |
      | toolCall   | #*                                                | the dangling call, retained |
      | toolResult | #"Interrupted.*result unknown.*verify.*repeating" | synthesized — durable       |
    And the log has entries matching:
      | level | event                     | session     | repair              |
      | :warn | :resume/transcript-repair | engine-room | :dangling-tool-call |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
