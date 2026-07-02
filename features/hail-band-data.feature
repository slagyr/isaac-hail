Feature: Hail band data survives prompt override and appears in delivery metadata
  Band frontmatter may declare a :data map — coordinates and context the recipient
  needs regardless of which prompt was delivered. Effective data merges band
  defaults with per-hail :params (params win) and interpolates {{var}} placeholders
  in string values.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: Band data appears in the metadata preamble when the body renders the prompt
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                                  |
      | session-tags | #{:project/warp-coil}                                  |
      | reach        | :one                                                   |
      | data         | {:bean-repo "isaac", :bean-id "{{bean-id}}"}           |
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
    And the sole pending hail EDN contains:
      | path   | value                                              |
      | prompt | Resonance climbing on primary, drift 0.03.         |
      | data   | {:bean-repo "isaac", :bean-id "isaac-iz3a", :coil "primary", :drift 0.03} |
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                         |
      | #"(?s).*Data:.*bean-repo.*isaac.*"             |
      | #"(?s).*bean-id.*isaac-iz3a.*"                  |
    And session "engine-room" has transcript matching:
      | message.role | message.content                              |
      | user         | #"(?s).*Resonance climbing on primary.*"    |

  Scenario: Band data survives an explicit prompt override
    Given the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                                        |
      | session-tags | #{:project/chess}                            |
      | reach        | :one                                         |
      | data         | {:bean-repo "isaac", :notification-comm "longwave"} |
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
      | type | content      | model  |
      | text | Acknowledged.| grover |
    When the config is loaded
    When isaac is run with "hail send --band bean-pickup --prompt 'Verifier needs help on iz3a.'"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                         |
      | #"(?s).*Data:.*bean-repo.*isaac.*"             |
      | #"(?s).*notification-comm.*longwave.*"          |
    And session "engine-room" has transcript matching:
      | message.role | message.content                        |
      | user         | #"(?s).*Verifier needs help on iz3a.*" |

  Scenario: Per-hail params override band data keys and pass through extras
    Given the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                                                     |
      | session-tags | #{:project/chess}                                         |
      | reach        | :one                                                      |
      | data         | {:bean-repo "isaac", :sector "alpha"}                    |
    And the isaac file "config/hail/bean-pickup.md" exists with:
      """
      Sector check.
      """
    When the config is loaded
    When isaac is run with "hail send --band bean-pickup --params '{:sector \"gamma\", :coil \"port\"}' --edn"
    Then the exit code is 0
    And the stdout contains "sector"
    And the stdout contains "gamma"
    And the stdout contains "coil"
    And the stdout contains "port"