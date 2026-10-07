Feature: Hail-driven session create (get-or-create)
  A create-enabled hail uses get-or-create for its target session.
  The :create flag is the descriptive/prescriptive toggle for the
  frequencies' tags:

    :create :never (default) - tags are a read-only FILTER over existing
                                    sessions. No match -> undeliverable at
                                    send, nothing queued.
    :create :if-missing            - MATCH-OR-CREATE. An existing matching
                                    session -> submitted turn waits for it;
                                    none -> the turn queue creates one under
                                    the resolved processing crew, applies the
                                    hail's :session-tags, and runs. (One-line:
                                    ":create :if-missing = create the addressed
                                    session if it doesn't exist.")

  Create is resolved by Agent's turn queue when it admits the turn, not by
  Hail. A matching session that is idle runs the turn immediately; busy ->
  the turn waits (never a sibling — preserves the crew's context); none ->
  the queue creates a session under the resolved :crew, tagging it with the
  hail's :session-tags and marking :origin {:kind :hail ...}.

  Default :create is :never.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the isaac EDN file "config/hail/warp-coil-callout.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | create       | :if-missing           |
      | with-crew    | bartholomew           |
    And the isaac file "config/hail/warp-coil-callout.md" exists with:
      """
      Resonance climbing.
      """

  Scenario: a create-enabled hail creates a tagged session and dispatches when none match
    Given the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    When isaac is run with "hail send --band warp-coil-callout"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value                 |
      | id                       | #turn-id               |
      | frequencies.create       | :if-missing            |
      | frequencies.session-tags | #{:project/warp-coil}  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then the following sessions match:
      | id        | crew        | tags                  | origin.kind |
      | session-1 | bartholomew | #{:project/warp-coil} | hail        |
    And session "session-1" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |

  Scenario: without create, no matching session is refused at send
    Given the isaac EDN file "config/hail/warp-coil-strict.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
    And the isaac file "config/hail/warp-coil-strict.md" exists with:
      """
      Resonance climbing.
      """
    When isaac is run with "hail send --band warp-coil-strict"
    Then the stderr contains "no session"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  Scenario: an existing matching session is bound instead of spawning a new one
    Given the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    When isaac is run with "hail send --band warp-coil-callout"
    Then the exit code is 0
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "session-1" does not exist
    And session "coil-work" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |

  Scenario: a create-enabled hail whose only matching session is in flight waits, no sibling
    Given the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And session "coil-work" is in flight
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    When isaac is run with "hail send --band warp-coil-callout"
    Then the stdout matches:
      | #"[a-z0-9]+":turn-id |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then the turn Hail submitted has:
      | path  | value |
      | state | :held |
    When the turn ends on session "coil-work"
    And the turn queue ticks at "2026-03-01T18:00:05"
    Then session "session-1" does not exist
    And session "coil-work" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |

  # isaac-e0t7: a band spelled with vector session-tags ([:ops], which the
  # schema allows) created a session whose vector tags matched nothing, so
  # every later hail on the band created another. Tags are a set however
  # they were written: the second hail finds the first session.
  Scenario: a band whose session-tags are a vector reuses the session it created
    Given the isaac EDN file "config/hail/ops-callout.edn" exists with:
      | path         | value       |
      | session-tags | [:ops]      |
      | create       | :if-missing |
      | with-crew    | bartholomew |
    And the isaac file "config/hail/ops-callout.md" exists with:
      """
      Ops check.
      """
    And the following model responses are queued:
      | type | content   | model  |
      | text | Checked.  | grover |
      | text | Again.    | grover |
    When isaac is run with "hail send --band ops-callout"
    Then the exit code is 0
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then the following sessions match:
      | id        | crew        | tags   | origin.kind |
      | session-1 | bartholomew | #{:ops} | hail       |
    When isaac is run with "hail send --band ops-callout"
    Then the exit code is 0
    When the turn queue ticks at "2026-03-01T18:05:00"
    Then the session count is 1
    And session "session-2" does not exist
    And session "session-1" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Ops check.      |
      | message | assistant    | Checked.        |
      | message | user         | Ops check.      |
      | message | assistant    | Again.          |
