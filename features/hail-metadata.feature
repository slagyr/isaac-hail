Feature: Hail delivery embeds metadata and params in the turn's system preamble
  Every hail delivered to a session opens its turn with a system preamble carrying
  standard, model-friendly context — the delivery-bound session id, hail id, thread,
  reply-to, submitter/origin, and the hail's :params as data — so autonomous handoffs
  work without the model re-threading data by hand.

  The instruction (the user turn :input) is unchanged: a band template rendered from
  :params, or the caller's explicit :prompt. :params are ALWAYS echoed in the preamble
  as data — even when a band template already consumed them — so they never silently
  drop on the explicit-prompt path. The bound session id is a delivery-time fact
  (reach-one binds a candidate at tick), so the preamble is enriched at delivery.

  (Design settled with Micah 2026-07-01: metadata lives in the system preamble, not the
  user input; band templating stays; params are always echoed.)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: A delivered hail's system preamble carries the metadata and params
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path      | value                                       |
      | id        | hail-1                                      |
      | session   | engine-room                                 |
      | crew      | bartholomew                                 |
      | thread-id | dilithium-thread-7                          |
      | prompt    | Recalibrate the port warp coil.             |
      | params    | {:coil "port", :submitter-session "bridge"} |
      | attempts  | 0                                           |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                    |
      | #"(?s).*[Ss]ession:\s*engine-room.*"       |
      | #"(?s).*[Hh]ail id:\s*hail-1.*"            |
      | #"(?s).*[Tt]hread:\s*dilithium-thread-7.*" |
      | #"(?s).*coil.*port.*"                      |
      | #"(?s).*submitter-session.*bridge.*"       |
    And session "engine-room" has transcript matching:
      | message.role | message.content                             |
      | user         | #"(?s).*Recalibrate the port warp coil\..*" |

  Scenario: A band hail renders the template into the instruction and echoes the params in the preamble
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
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
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                              |
      | #"(?s).*[Ss]ession:\s*engine-room.*" |
      | #"(?s).*coil.*primary.*"             |
      | #"(?s).*drift.*0\.03.*"              |

  Scenario: An exact-session handback surfaces the submitter session and reply-to as the return address
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content       | model  |
      | text | Reporting in. | grover |
    And the isaac EDN file hail/deliveries/hail-2.edn exists with:
      | path              | value                             |
      | id                | hail-2                            |
      | session           | engine-room                       |
      | crew              | bartholomew                       |
      | thread-id         | dilithium-thread-7                |
      | reply-to          | hail-1                            |
      | submitter-session | bridge                            |
      | prompt            | Report coil status to the bridge. |
      | params            | {:coil "port"}                    |
      | attempts          | 0                                 |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                    |
      | #"(?s).*[Ss]ession:\s*engine-room.*"       |
      | #"(?s).*[Ss]ubmitter.?session:\s*bridge.*" |
      | #"(?s).*[Rr]eply.?to:\s*hail-1.*"          |
      | #"(?s).*coil.*port.*"                      |

  Scenario: A reach-one hail bound at delivery shows the delivery-selected session id in the preamble
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
    And the isaac EDN file hail/deliveries/hail-4.edn exists with:
      | path       | value                                                                       |
      | id         | hail-4                                                                      |
      | prompt     | Status report?                                                              |
      | params     | {:sector "gamma"}                                                           |
      | candidates | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] |
      | attempts   | 0                                                                           |
    When the hail delivery worker ticks
    And the turn ends on session "bridge"
    Then the hail turn on session "bridge" has a system preamble matching:
      | pattern                         |
      | #"(?s).*[Ss]ession:\s*bridge.*" |
      | #"(?s).*sector.*gamma.*"        |

  Scenario: A hail with a prompt and no params is delivered as-is, with metadata and no params section
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content            | model  |
      | text | Answering all stop.| grover |
    And the isaac EDN file hail/deliveries/hail-5.edn exists with:
      | path      | value              |
      | id        | hail-5             |
      | session   | engine-room        |
      | crew      | bartholomew        |
      | thread-id | dilithium-thread-7 |
      | prompt    | All stop.          |
      | attempts  | 0                  |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                              |
      | #"(?s).*[Ss]ession:\s*engine-room.*" |
      | #"(?s).*[Hh]ail id:\s*hail-5.*"      |
    And the hail turn on session "engine-room" has a system preamble not matching:
      | pattern          |
      | #"(?i).*param.*" |
    And session "engine-room" has transcript matching:
      | message.role | message.content       |
      | user         | #"(?s).*All stop\..*" |
