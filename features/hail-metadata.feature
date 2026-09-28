Feature: Hail submission embeds metadata and params in the turn's system preamble
  Every hail submits its turn with a system preamble carrying standard,
  model-friendly context — the hail id, thread, reply-to, submitter/origin,
  and the hail's :params as data — so autonomous handoffs work without the
  model re-threading data by hand.

  The instruction (the turn's :input) is unchanged: a band template rendered from
  :params, or the caller's explicit :prompt. :params are ALWAYS echoed in the preamble
  as data — even when a band template already consumed them — so they never silently
  drop on the explicit-prompt path. The preamble is built at send time, riding the
  turn as :preamble.

  (Design settled with Micah 2026-07-01: metadata lives in the system preamble, not the
  user input; band templating stays; params are always echoed.)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: A submitted hail's system preamble carries the metadata and params
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When isaac is run with "hail send --session engine-room --prompt 'Recalibrate the port warp coil.' --params '{:coil \"port\", :submitter-session \"bridge\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                                                                                                |
      | id       | #turn-id                                                                                             |
      | preamble | #"(?s).*[Hh]ail id:\s*[a-z0-9]+.*[Tt]hread:\s*[a-z0-9]+.*coil.*port.*submitter-session.*bridge.*"  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | message.role | message.content                             |
      | user         | #"(?s).*Recalibrate the port warp coil\..*" |
      | assistant    | On it.                                       |

  @wip
  Scenario: A band hail renders the template into the instruction and echoes the params in the preamble
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content     | model  |
      | text | Aye, on it. | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                                        |
      | id       | #turn-id                                     |
      | input    | Resonance climbing on primary, drift 0.03.   |
      | preamble | #"(?s).*coil.*primary.*drift.*0\.03.*"       |

  @wip
  Scenario: An exact-session handback surfaces the reply-to as the return address
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content       | model  |
      | text | Reporting in. | grover |
    And the isaac EDN file "turns/hail-1.edn" exists with:
      | path   | value                                             |
      | id     | hail-1                                            |
      | state  | :finished                                         |
      | origin | {:source :hail :thread-id "dilithium-thread-7"}  |
    When isaac is run with "hail send --session engine-room --prompt 'Report coil status to the bridge.' --params '{:coil \"port\"}' --reply-to hail-1"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                          |
      | id                | #turn-id                                      |
      | origin.reply-to   | hail-1                                        |
      | origin.thread-id  | dilithium-thread-7                            |
      | preamble          | #"(?s).*[Rr]eply.?to:\s*hail-1.*coil.*port.*" |

  @wip
  Scenario: A hail with a prompt and no params is delivered as-is, with metadata and no params section
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content             | model  |
      | text | Answering all stop. | grover |
    When isaac is run with "hail send --session engine-room --prompt 'All stop.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                  | #comment                 |
      | id       | #turn-id               |                          |
      | input    | All stop.              |                          |
      | preamble | #"(?s)(?!.*[Pp]arams).*" | no params section at all |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | message.role | message.content        |
      | user         | #"(?s).*All stop\..*" |
      | assistant    | Answering all stop.     |

  @wip
  Scenario: the turn's preamble carries per-hail facts only — identity is ambient (isaac-sx4g)
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When isaac is run with "hail send --session engine-room --prompt 'Recalibrate the port warp coil.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                        | #comment                               |
      | id       | #turn-id                     |                                        |
      | preamble | #"(?s).*[Tt]hread.*"         | per-hail facts are present             |
      | preamble | #"(?sm)(?!.*^Session:).*"    | no session line — identity is ambient  |
