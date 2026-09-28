Feature: Hail band prompt templating with params

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: Band body is a template rendered with the hail's params to produce the prompt; explicit prompt overrides
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
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
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":first-id |
    And the turn Hail submitted has:
      | path          | value                                       |
      | id            | #first-id                                   |
      | input         | Resonance climbing on primary, drift 0.03.  |
      | origin.params | {:coil "primary", :drift 0.03}              |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}' --prompt 'Status report?'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":second-id |
    And the turn Hail submitted has:
      | path          | value                           |
      | id            | #second-id                      |
      | input         | Status report?                  |
      | origin.params | {:coil "primary", :drift 0.03}  |

  @wip
  Scenario: The rendered prompt from a templated band hail becomes the input to the receiving turn
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
      | session-tags | #{:project/warp-coil} |
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
      | type | content      | model  |
      | text | On the coil. | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"secondary\", :drift 0.07}'"
    Then the exit code is 0
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content                              |
      | message | user         | Resonance climbing on secondary, drift 0.07. |
      | message | assistant    | On the coil.                                  |

  @wip
  Scenario: Sending a hail to a templated band returns the turn id and submits a turn with the rendered prompt, params, and auto thread-id
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
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
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                       |
      | id               | #turn-id                                    |
      | input            | Resonance climbing on primary, drift 0.03.  |
      | origin.params    | {:coil "primary", :drift 0.03}              |
      | origin.thread-id | #turn-id                                    |

  @wip
  Scenario: An agent can retrieve a prior hail turn's rendered input via turns show, then send a follow-up on the thread using new params
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
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
    And the isaac EDN file "turns/turn-1.edn" exists with:
      | path   | value                                                                                                        |
      | id     | turn-1                                                                                                      |
      | state  | :finished                                                                                                   |
      | input  | Resonance climbing on secondary, drift 0.07.                                                               |
      | origin | {:source :hail :thread-id "dilithium-thread-7" :params {:coil "secondary" :drift 0.07} :reply-to "hail-42"} |
    When isaac is run with "turns show turn-1"
    Then the stdout matches:
      | #"(?s).*Resonance climbing on secondary, drift 0.07\..*dilithium-thread-7.*hail-42.*" |
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"tertiary\", :drift 0.11}' --reply-to turn-1"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":new-turn-id |
    And the turn Hail submitted has:
      | path             | value                                        |
      | id               | #new-turn-id                                 |
      | input            | Resonance climbing on tertiary, drift 0.11.  |
      | origin.params    | {:coil "tertiary", :drift 0.11}              |
      | origin.thread-id | dilithium-thread-7                           |
      | origin.reply-to  | turn-1                                       |

  @wip
  Scenario: The turn context for the receiving agent includes the full hail record with rendered prompt and params
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                  |
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
    When the config is loaded
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"secondary\", :drift 0.07}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                        |
      | id               | #turn-id                                     |
      | input            | Resonance climbing on secondary, drift 0.07. |
      | origin.params    | {:coil "secondary", :drift 0.07}             |
      | origin.thread-id | #turn-id                                     |
