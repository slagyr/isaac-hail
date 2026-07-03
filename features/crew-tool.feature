Feature: Hail crew tool
  The `hail-send` tool lets an LLM in a turn dispatch hails from
  inside its reasoning loop. Crews opt in via :tools.allow. The
  sent hail's :from records the calling crew's identity.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

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
    Then the sole pending hail EDN contains:
      | path        | value                 |
      | id          | <short-uuid>          |
      | frequencies | {:band "bean-pickup"} |
      | params      | {:n 1}                |
      | from        | :crew/main            |

  Scenario: crew without hail-send in allow list cannot invoke it
    Given the following sessions exist:
      | name      |
      | work-sess |
    When the user sends "anything" on session "work-sess"
    Then the prompt does not have tools:
      | name      |
      | hail-send |

  Scenario: hail-send with an explicit session equal to a band name is rejected (isaac-8lhv)
    A band name is a selector, not a session. Passing it as an explicit session
    targets a session that never exists -> silent dead-letter. The tool rejects
    it with an actionable error so the model self-corrects in-turn.
    Given the crew "bartholomew" allows tools: "hail-send"
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
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
      | echo  | text      | ok                                                              |
    When the user sends "hand off" on session "engine-room"
    Then the last hail-send tool result is an error matching #"(?i).*engineering-intercom.*band.*not a session.*"
    And there are no pending hails

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
    And there are no pending hails

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
    Then the sole pending hail EDN contains:
      | path        | value                     |
      | frequencies | {:session ["engine-room"]} |
