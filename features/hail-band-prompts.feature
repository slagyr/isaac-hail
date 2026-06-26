Feature: Hail band prompt templating with params

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Band body is a template rendered with the hail's params to produce the prompt; explicit prompt overrides
    Given an Isaac root at "target/test-state"
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}'"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path   | value                                      |
      | prompt | Resonance climbing on primary, drift 0.03. |
      | params | {:coil "primary", :drift 0.03}             |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}' --prompt 'Status report?'"
    Then the exit code is 0
    And pending hail 2 EDN contains:
      | path   | value                  |
      | prompt | Status report?         |
      | params | {:coil "primary", :drift 0.03} |

  Scenario: The rendered prompt from a templated band hail becomes the input to the receiving turn
    Given an Isaac root at "target/test-state"
    And default Grover setup
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                  |
      | model | grover                 |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"secondary\", :drift 0.07}'"
    Then the exit code is 0
    When the hail router ticks
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content                          |
      | message | user         | Resonance climbing on secondary, drift 0.07. |

  Scenario: Sending a hail to a templated band returns the assigned id and creates a record with the rendered prompt and params (plus auto thread-id)
    Given an Isaac root at "target/test-state"
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}'"
    Then the exit code is 0
    And the stdout is a bare hail id
    And the sole pending hail EDN contains:
      | path      | value                                      |
      | prompt    | Resonance climbing on primary, drift 0.03. |
      | params    | {:coil "primary", :drift 0.03}             |
      | thread-id | <short-uuid>                               |

  Scenario: An agent can use hail_get to retrieve a prior templated hail's rendered prompt and params, then send a follow-up on the thread using new params
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/engineering-intercom.md" exists with:
      """
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    And the EDN isaac file "hail/delivered/hail-1.edn" exists with:
      | path      | value                                        |
      | id        | hail-1                                       |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
    When an agent calls the hail_get tool with id "hail-1"
    Then it returns the hail record containing:
      | path      | value                                        |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"tertiary\", :drift 0.11}' --reply-to hail-1"
    Then the exit code is 0
    And the sole pending hail EDN contains:
      | path      | value                                        |
      | prompt    | Resonance climbing on tertiary, drift 0.11.  |
      | params    | {:coil "tertiary", :drift 0.11}              |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-1                                       |

  Scenario: The turn context for the receiving agent includes the full hail record with rendered prompt and params
    Given an Isaac root at "target/test-state"
    Given the setup for a templated hail delivered to a session
    When the turn is charged for the session
    Then the turn input is the rendered prompt
    And the associated hail context contains the params, thread-id, and reply-to
