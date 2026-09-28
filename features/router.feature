Feature: Hail addressing
  `isaac hail send` resolves a hail's :frequencies to a target session
  synchronously, before anything is queued. A band's session-tags,
  explicit :crew, explicit :session, and combinations thereof narrow the
  live session pool; :with-crew overrides the processing crew. Addressing
  that cannot resolve to at least one existing session is refused at send
  — nothing is queued, and (for band-declared selectors) a WARN
  hail/undeliverable event is logged. Addressing that resolves submits
  ONE turn to Agent's durable queue; that turn's admission (crew, bound
  session) plays out when the turn queue ticks.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: a session activated via sessions set is routable by band session-tags
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
    And the following model responses are queued:
      | type | content | model  |
      | text | Aye.    | grover |
    When isaac is run with "sessions set relay.tags.role/engineer"
    Then the exit code is 0
    When isaac is run with "hail send --band engineering-intercom --prompt 'Engineering intercom check.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value             |
      | id                       | #turn-id          |
      | frequencies.session-tags | #{:role/engineer} |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "relay" has transcript matching:
      | type    | message.role | message.content             |
      | message | user         | Engineering intercom check. |
      | message | assistant    | Aye.                         |

  @wip
  Scenario: a reach-one band matching exactly one session binds immediately
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Dilithium leak reported.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name        | crew        | tags              |
      | engine-room | bartholomew | #{:role/engineer} |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When isaac is run with "hail send --band engineering-intercom --params '{:dilithium-leak true}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value                   |
      | id                       | #turn-id                |
      | frequencies.session-tags | #{:role/engineer}       |
      | origin.params            | {:dilithium-leak true}  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content          |
      | message | user         | Dilithium leak reported. |
      | message | assistant    | On it.                    |

  @wip
  Scenario: a frequency :crew selects sessions of that crew
    Given the isaac EDN file "config/crew/marvin.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name         | crew   |
      | agile-voyage | main   |
      | side-job     | marvin |
    And the following model responses are queued:
      | type | content     | model  |
      | text | Backlogged. | grover |
    When isaac is run with "hail send --crew main --prompt 'Work the backlog.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value              |
      | id               | #turn-id           |
      | frequencies.crew | "main"             |
      | input            | Work the backlog.  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "agile-voyage" has transcript matching:
      | type    | message.role | message.content   |
      | message | user         | Work the backlog. |
      | message | assistant    | Backlogged.        |

  @wip
  Scenario: a direct session frequency binds to that exact session only
    Given the isaac EDN file "config/crew/mavis.edn" exists with:
      | path  | value              |
      | model | grover             |
      | tags  | #{:role/navigator} |
    And the following sessions exist:
      | name           | crew  |
      | charted-course | mavis |
      | side-quest     | mavis |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Bearing set. | grover |
    When isaac is run with "hail send --session charted-course --prompt 'Adjust bearing 12 degrees.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path    | value                       |
      | id      | #turn-id                    |
      | session | :charted-course             |
      | input   | Adjust bearing 12 degrees.  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "charted-course" has transcript matching:
      | type    | message.role | message.content            |
      | message | user         | Adjust bearing 12 degrees. |
      | message | assistant    | Bearing set.                |

  @wip
  Scenario: combined band and session-tag intersect to one bound delivery
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance drift check.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name           | crew        | tags                                 |
      | engine-room    | bartholomew | #{:role/engineer}                    |
      | coil-tinkering | bartholomew | #{:role/engineer :project/warp-coil} |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When isaac is run with "hail send --band engineering-intercom --session-tag project/warp-coil --params '{:resonance-drift 0.03}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path                     | value                  |
      | id                       | #turn-id               |
      | frequencies.session-tags | #{:project/warp-coil}  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "coil-tinkering" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Resonance drift check. |
      | message | assistant    | On it.                 |

  @wip
  Scenario: a band :crew selects sessions whose crew matches
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path | value         |
      | crew | "bartholomew" |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Status check.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
      | galley  | hieronymus  |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When isaac is run with "hail send --band engineering-intercom --params '{:n 1}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status check.    |
      | message | assistant    | On it.           |

  @wip
  Scenario: a frequency with no session selector is refused at send
    When isaac is run with "hail send --prompt 'Orphan reach.'"
    Then the stderr contains "addressing"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  @wip
  Scenario: processing crew comes from the matched session
    Given the following sessions exist:
      | name        | crew |
      | engine-room | main |
    And the following model responses are queued:
      | type | content  | model  |
      | text | Nominal. | grover |
    When isaac is run with "hail send --session engine-room --prompt 'Check the gauges.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Check the gauges. |
      | message | assistant    | Nominal.            |

  @wip
  Scenario: an unknown band is refused at send
    When isaac is run with "hail send --band phantom-band --params '{:n 1}'"
    Then the stderr contains "unknown band: phantom-band"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  @wip
  Scenario: a reach-one band with no matching session is refused at send
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             | #comment                   |
      | model | grover            |                            |
      | tags  | #{:role/botanist} | no engineer-tagged session |
    And the following sessions exist:
      | name       | crew       |
      | galley | hieronymus |
    When isaac is run with "hail send --band engineering-intercom --params '{:n 1}'"
    Then the stderr contains "no session"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  @wip
  Scenario: an undeliverable hail logs a WARN hail/undeliverable event at send (isaac-axzg)
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             | #comment                   |
      | model | grover            |                            |
      | tags  | #{:role/botanist} | no engineer-tagged session |
    And the following sessions exist:
      | name       | crew       |
      | galley | hieronymus |
    When isaac is run with "hail send --band engineering-intercom --params '{:n 1}'"
    Then the stderr contains "no session"
    And the exit code is 1
    And the log has entries matching:
      | level | event               | band                  | reason         |
      | :warn | :hail/undeliverable | engineering-intercom  | :no-recipients |

  # --- Conform :frequencies onto the shared session selector (isaac-c58s) ---
  # :frequencies holds the same flat map the prompt command builds (select keys
  # + :with-* override keys). --with-crew overrides the processing crew.

  @wip
  Scenario: --with-crew overrides the processing crew
    Given the following sessions exist:
      | name        | crew |
      | engine-room | main |
    And the isaac EDN file "config/crew/navigator.edn" exists with:
      | path  | value  |
      | model | grover |
    Given stdin is:
      """
      {:frequencies {:session [:engine-room] :with-crew :navigator}
       :prompt      "Check the gauges."}
      """
    When isaac is run with "hail send - --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                                            |
      | frequencies | {:session [:engine-room] :with-crew :navigator}  |
      | input       | Check the gauges.                                |
