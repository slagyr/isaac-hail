Feature: Startup resume of interrupted hail turns
  On startup, the bridge scans the durable turn markers (isaac-7li9) and
  resumes what a restart interrupted. A hail-sourced marker is a work order:
  it never goes stale and it resumes exactly like a cron or comm marker — the
  turn is handed to the turn queue and completes in its own session
  (isaac-yxch, isaac-6doh). Nothing is re-queued to hail/deliveries/ and no
  delivery payload lives in the marker: the delivery was delivered at bind
  (isaac-9azm) and that receipt is the delivery's whole story. Transcript
  repair runs before the resume: dangling toolCalls get a synthesized result
  ("result unknown — verify before repeating") so the re-driven turn never
  presents the orphaned-tool-call shape providers reject (isaac-63f3 family).
  The resume scan runs before the delivery worker's first tick. (isaac-vdfc,
  recut under isaac-9azm)

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
  Scenario: a suspended hail marker resumes in its own session — the receipt written at bind stands (isaac-2xj5, isaac-9azm)
    Given session "engine-room" has transcript:
      | type    | message.role | message.content |
      | message | user         | Seal the leak.  |
    And the isaac EDN file "hail/delivered/hail-1.edn" exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path           | value                | #comment                          |
      | source         | :hail                |                                   |
      | suspended      | true                 |                                   |
      | boundary       | :clean               |                                   |
      | started-at     | 2026-04-21T09:00:00Z | an hour old — work orders never go stale |
      | interrupted-at | 2026-04-21T09:59:00Z |                                   |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the log has entries matching:
      | level | event            | session     |
      | :info | :turn.queue/held | engine-room |
    And no turn marker exists for session "engine-room"
    When the turn queue ticks at "2026-04-21T10:00:05Z"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content    | #comment                   |
      | message | user         | Seal the leak.     |                            |
      | message | user         | #".*interrupted.*" | resume note                |
      | message | assistant    | Sealing now.       | resumed in its own session |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a dangling toolCall is repaired with a synthesized result before resume (isaac-9azm)
    Given session "engine-room" has transcript:
      | type     | message.role | message.content | id   |
      | message  | user         | Seal the leak.  |      |
      | toolCall | assistant    |                 | tc-1 |
    And the isaac EDN file "hail/delivered/hail-1.edn" exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path      | value    |
      | source    | :hail    |
      | suspended | true     |
      | boundary  | :unclean |
    And the following model responses are queued:
      | type | content     | model  |
      | text | Sealed now. | grover |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then session "engine-room" has transcript matching:
      | type     | message.role | message.content                                     | #comment                    |
      | message  | user         | Seal the leak.                                      |                             |
      | toolCall | assistant    | #*                                                  | the dangling call, retained |
      | message  | toolResult   | #"Interrupted.*result unknown.*verify.*repeating.*" | synthesized — durable       |
    And the log has entries matching:
      | level | event                     | session     | repair              |
      | :warn | :resume/transcript-repair | engine-room | :dangling-tool-call |
    When the turn queue ticks at "2026-04-21T10:00:05Z"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Sealed now.     |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |

  @wip
  Scenario: a cancelled hail marker is dropped at resume — the receipt stands, nothing is re-queued (isaac-9azm)
    Given the isaac EDN file "hail/delivered/hail-1.edn" exists with:
      | path          | value          |
      | id            | hail-1         |
      | prompt        | Seal the leak. |
      | crew          | bartholomew    |
      | bound-session | :engine-room   |
    And the isaac EDN file "sessions/turns/engine-room.edn" exists with:
      | path      | value |
      | source    | :hail |
      | cancelled | true  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then no turn marker exists for session "engine-room"
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path | value  |
      | id   | hail-1 |
    And the log has entries matching:
      | level | event                 | requeued | dropped |
      | :info | :resume/scan-complete | 0        | 1       |
