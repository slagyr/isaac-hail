Feature: Hail crew tool
  The `hail-send` tool lets an LLM in a turn dispatch hails from inside its
  reasoning loop. Crews opt in via :tools.allow. The sent hail's
  :origin.from records the calling crew's identity. Like the CLI, the tool
  submits ONE turn to Agent's durable queue; an undeliverable address is
  rejected in-turn with an actionable error, nothing queued.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/hail/bean-pickup.edn" exists with:
      | path         | value                |
      | session-tags | #{:project/galley}  |
    And the isaac file "config/hail/bean-pickup.md" exists with:
      """
      Pick up the beans.
      """
    And the isaac EDN file "config/crew/wormwood.edn" exists with:
      | path  | value                |
      | model | grover               |
      | tags  | #{:project/galley}  |
    And the following sessions exist:
      | name       | crew        | tags                 |
      | galley | wormwood | #{:project/galley}  |

  @wip
  Scenario: crew with hail-send allowed dispatches a hail from a turn
    Given the crew "main" allows tools: "hail-send"
    And the following sessions exist:
      | name      |
      | work-sess |
    And the following model responses are queued:
      | model | tool_call | arguments                                     |
      | echo  | hail-send | {"band": "bean-pickup", "params": {"n": 1}} |
      | model | type      | content                                       |
      | echo  | text      | Done.                                         |
    When the user sends "send a hail" on session "work-sess"
    Then the turn Hail submitted has:
      | path             | value         |
      | origin.band      | "bean-pickup" |
      | origin.params    | {:n 1}        |
      | origin.from      | :crew/main    |

  Scenario: crew without hail-send in allow list cannot invoke it
    Given the following sessions exist:
      | name      |
      | work-sess |
    When the user sends "anything" on session "work-sess"
    Then the prompt does not have tools:
      | name      |
      | hail-send |

  @wip
  Scenario: hail-send with an explicit session equal to a band name is rejected (isaac-8lhv)
    A band name is a selector, not a session. Passing it as an explicit session
    targets a session that never exists -> silent dead-letter. The tool rejects
    it with an actionable error so the model self-corrects in-turn, and no turn
    is queued for it.
    Given the crew "bartholomew" allows tools: "hail-send"
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | model | tool_call | arguments                                                       |
      | echo  | hail-send | {"session": "engineering-intercom", "params": {"bean-id": "x"}} |
      | model | type      | content                                                         |
      | echo  | text      | ok                                                               |
    When the user sends "hand off" on session "engine-room"
    Then the last hail-send tool result is an error matching #"(?i).*engineering-intercom.*band.*not a session.*"

  @wip
  Scenario: hail-send with an explicit session that names nothing is rejected (isaac-8lhv)
    Given the crew "bartholomew" allows tools: "hail-send"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | model | tool_call | arguments                                              |
      | echo  | hail-send | {"session": "first-watch", "params": {"bean-id": "x"}} |
      | model | type      | content                                                |
      | echo  | text      | ok                                                     |
    When the user sends "hand off" on session "engine-room"
    Then the last hail-send tool result is an error matching #"(?i).*no session .first-watch.*"

  @wip
  Scenario: hail-send with a real explicit session still dispatches (isaac-8lhv)
    Given the crew "bartholomew" allows tools: "hail-send"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | model | tool_call | arguments                                              |
      | echo  | hail-send | {"session": "engine-room", "params": {"bean-id": "x"}} |
      | model | type      | content                                                |
      | echo  | text      | ok                                                     |
    When the user sends "hand off" on session "engine-room"
    Then the turn Hail submitted has:
      | path          | value          |
      | session       | :engine-room   |
      | origin.params | {:bean-id "x"} |
