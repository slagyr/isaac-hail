Feature: Hail band data survives prompt override and appears in the turn's preamble
  Band frontmatter may declare a :data map — coordinates and context the recipient
  needs regardless of which prompt was delivered. Effective data merges band
  defaults with per-hail :params (params win) and interpolates {{var}} placeholders
  in string values. The merged data rides the submitted turn as :origin.data and
  the metadata preamble.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: Band data appears in the metadata preamble when the body renders the prompt
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                         |
      | session-tags | #{:project/warp-coil}                         |
      | data         | {:bean-repo "isaac", :bean-id "{{bean-id}}"}  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                  |
      | model | grover                 |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content   | model  |
      | text | On it.    | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03, :bean-id \"isaac-iz3a\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path        | value                                                                     |
      | id          | #turn-id                                                                  |
      | input       | Resonance climbing on primary, drift 0.03.                               |
      | origin.data | {:bean-repo "isaac", :bean-id "isaac-iz3a", :coil "primary", :drift 0.03} |
      | preamble    | #"(?s).*Data:.*bean-repo.*isaac.*bean-id.*isaac-iz3a.*"                  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | message.role | message.content                           |
      | user         | #"(?s).*Resonance climbing on primary.*"  |
      | assistant    | On it.                                     |

  Scenario: Band data survives an explicit prompt override
    Given the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                                                |
      | session-tags | #{:project/chess}                                    |
      | data         | {:bean-repo "isaac", :notification-comm "longwave"}  |
    And the isaac file "config/hail/bean-pickup.md" exists with:
      """
      Pick up the beans in the galley.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:project/chess} |
    And the following sessions exist:
      | name        | crew        | tags              |
      | engine-room | bartholomew | #{:project/chess} |
    And the following model responses are queued:
      | type | content       | model  |
      | text | Acknowledged. | grover |
    When the config is loaded
    When isaac is run with "hail send --band bean-pickup --prompt 'Verifier needs help on iz3a.'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                                                             |
      | id       | #turn-id                                                          |
      | input    | Verifier needs help on iz3a.                                      |
      | preamble | #"(?s).*Data:.*bean-repo.*isaac.*notification-comm.*longwave.*"  |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | message.role | message.content                         |
      | user         | #"(?s).*Verifier needs help on iz3a.*"  |
      | assistant    | Acknowledged.                            |

  Scenario: Per-hail params override band data keys and pass through extras
    Given the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                                  |
      | session-tags | #{:project/chess}                      |
      | data         | {:bean-repo "isaac", :sector "alpha"}  |
    And the isaac file "config/hail/bean-pickup.md" exists with:
      """
      Sector check.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:project/chess} |
    And the following sessions exist:
      | name        | crew        | tags              |
      | engine-room | bartholomew | #{:project/chess} |
    When the config is loaded
    When isaac is run with "hail send --band bean-pickup --params '{:sector \"gamma\", :coil \"port\"}' --edn"
    Then the exit code is 0
    And the stdout contains "sector"
    And the stdout contains "gamma"
    And the stdout contains "coil"
    And the stdout contains "port"

  Scenario: A param overrides the same-named band data key in the delivered preamble
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                    |
      | session-tags | #{:project/warp-coil}                    |
      | data         | {:coil "port", :plan-hail "engine-plan"} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Check the coil.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"starboard\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path     | value                                               |
      | id       | #turn-id                                            |
      | preamble | #"(?s).*coil.*starboard.*plan-hail.*engine-plan.*" |

  Scenario: Band data values interpolate params
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | data         | {:bean "{{bean-id}}"} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Work the bean.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:bean-id \"isaac-42\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path        | value                                    |
      | id          | #turn-id                                 |
      | origin.data | {:bean "isaac-42", :bean-id "isaac-42"}  |

  Scenario: A band without declared data does not persist params as data
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path          | value                           |
      | id            | #turn-id                        |
      | input         | Resonance climbing on primary.  |
      | origin.params | {:coil "primary"}               |
      | origin.data   |                                  |

  Scenario: config validate accepts a band with a data map and rejects a non-map data
    Given an Isaac root at "isaac-state"
    And config file "crew/ops.edn" containing:
      """
      {:model :grover}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "echo" :provider :grover :context-window 32768}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    And config file "hail/good-band.edn" containing:
      """
      {:session-tags [:project/chess]
       :data         {:bean-repo "git@example.com:acme/chess.git"}}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0
    Given config file "hail/bad-band.edn" containing:
      """
      {:session-tags [:project/chess]
       :data         "not-a-map"}
      """
    When isaac is run with "config validate"
    Then the stderr contains "data"
    And the exit code is 1
