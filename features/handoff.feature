@wip
Feature: Hail hands a message to Agent's turn queue
  Hail is stateless. A send expands the band (template, params, data,
  metadata preamble), checks the address, submits ONE turn to Agent's
  durable queue, and prints Agent's turn id — there is no separate hail
  id. The message's facts ride the turn as an opaque :origin
  ({:source :hail :from … :principal … :thread-id … :reply-to … :params …
  :data …}) and the preamble as the turn's :preamble. A busy target waits
  in Agent's queue; Hail keeps nothing and retries nothing. A hail that
  can never be delivered is refused at send and nothing is queued. A
  reply names a turn id and inherits that turn's thread. (isaac-ex4q)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Seal the {{coil}} leak.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |

  Scenario: a band hail submits one turn to Agent and prints its turn id
    Given the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value                    |
      | id                       | #turn-id                 |
      | state                    | :queued                  |
      | input                    | Seal the primary leak.   |
      | frequencies.session-tags | #{:project/warp-coil}    |
      | origin.source            | :hail                    |
      | origin.from              | :cli                     |
      | origin.thread-id         | #turn-id                 |
      | origin.params            | {:coil "primary"}        |
      | preamble                 | #"(?s).*coil.*primary.*" |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Seal the primary leak. |
      | message | assistant    | Sealing now.           |

  Scenario: a busy target waits in Agent's queue, not in Hail
    Given the following model responses are queued:
      | type | content      | model  | wait |
      | text | Hold fast    | grover | true |
      | text | Sealing now. | grover |      |
    When the user sends "keep watch" on session "engine-room"
    And isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}'"
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the turn Hail submitted has:
      | path  | value |
      | state | :held |
    When the turn ends on session "engine-room"
    And the turn queue ticks at "2026-03-01T18:00:05"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Seal the primary leak. |
      | message | assistant    | Sealing now.           |

  Scenario: an undeliverable hail is refused at send and nothing is queued
    Given the isaac EDN file "config/hail/dry-dock.edn" exists with:
      | path         | value                |
      | session-tags | #{:project/dry-dock} |
      | create       | :never               |
    When isaac is run with "hail send --band ghost-band --prompt 'hello'"
    Then the stderr contains "unknown band: ghost-band"
    And the exit code is 1
    When isaac is run with "hail send --band dry-dock --prompt 'hello'"
    Then the stderr contains "no session"
    And the exit code is 1
    When isaac is run with "hail send --session ghost-room --prompt 'hello'"
    Then the stderr contains "no session: ghost-room"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  Scenario: a reply names a turn id and inherits that turn's thread
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}'"
    Then the stdout matches:
      | #"[a-z0-9]+":first-id |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"aft\"}' --reply-to #first-id"
    Then the stdout matches:
      | #"[a-z0-9]+":second-id |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"bow\"}' --reply-to #second-id"
    Then the turn Hail submitted has:
      | path             | value      |
      | origin.reply-to  | #second-id |
      | origin.thread-id | #first-id  |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"bow\"}' --reply-to ghost-1"
    Then the stderr contains "turn not found: ghost-1"
    And the exit code is 1

  Scenario: a repeated idempotency key returns the same turn id
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}' --idempotency-key ci-run-7"
    Then the stdout matches:
      | #"[a-z0-9]+":turn-id |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}' --idempotency-key ci-run-7"
    Then the stdout matches:
      | #turn-id |
    And the exit code is 0
