@wip
Feature: Hail bands carry a data map delivered with every hail
  Band files may declare :data — coordinates the recipient needs regardless of
  the instruction text (bean-repo, notification-comm, sibling band names, ...).
  Effective data is (merge band-data params) with {{param}} interpolation in
  data values. It rides the delivery metadata preamble — so it survives an
  explicit :prompt override — and is persisted on the hail record.

  (Bean: isaac-iz3a. Design settled with Micah 2026-07-02: instructions and
  data are separate channels; overriding the prompt replaces instructions,
  never data.)

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Band data is persisted on the record and survives a prompt override
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                                                  |
      | session-tags | #{:project/warp-coil}                                                  |
      | reach        | :one                                                                   |
      | data         | {:bean-repo "git@example.com:acme/warp.git", :plan-hail "engine-plan"} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --prompt 'Status report?' --params '{:coil \"primary\"}'"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path   | value                                                                  |
      | prompt | Status report?                                                         |
      | data   | {:bean-repo "git@example.com:acme/warp.git", :plan-hail "engine-plan"} |

  Scenario: Delivered band data appears in the metadata preamble even when the prompt is overridden
    Given default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                                                  |
      | session-tags | #{:project/warp-coil}                                                  |
      | reach        | :one                                                                   |
      | data         | {:bean-repo "git@example.com:acme/warp.git", :plan-hail "engine-plan"} |
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
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --prompt 'Status report?'"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                     |
      | #"(?s).*bean-repo.*acme/warp\.git.*"        |
      | #"(?s).*plan-hail.*engine-plan.*"           |
    And session "engine-room" has transcript matching:
      | message.role | message.content         |
      | user         | #"(?s).*Status report\?.*" |

  Scenario: Without a prompt override the band body still renders and the data is still delivered
    Given default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                      |
      | session-tags | #{:project/warp-coil}                      |
      | reach        | :one                                       |
      | data         | {:bean-repo "git@example.com:acme/warp.git"} |
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
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\"}'"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                              |
      | #"(?s).*bean-repo.*acme/warp\.git.*" |
    And session "engine-room" has transcript matching:
      | message.role | message.content                            |
      | user         | #"(?s).*Resonance climbing on primary\..*" |

  Scenario: A param overrides the same-named band data key in the effective data
    Given default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                                    |
      | session-tags | #{:project/warp-coil}                    |
      | reach        | :one                                     |
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
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"starboard\"}'"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                            |
      | #"(?s).*coil.*starboard.*"         |
      | #"(?s).*plan-hail.*engine-plan.*"  |

  Scenario: Band data values interpolate {{params}}
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
      | data         | {:bean "{{bean-id}}"} |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Work the bean.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:bean-id \"isaac-42\"}'"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path | value             |
      | data | {:bean "isaac-42"} |

  Scenario: config validate accepts a band with a :data map and rejects a non-map :data
    Given config file "hail/good-band.edn" containing:
      """
      {:session-tags [:project/chess]
       :reach        :one
       :data         {:bean-repo "git@example.com:acme/chess.git"}}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0
    Given config file "hail/bad-band.edn" containing:
      """
      {:session-tags [:project/chess]
       :reach        :one
       :data         "not-a-map"}
      """
    When isaac is run with "config validate"
    Then the stderr contains "data"
    And the exit code is 1
