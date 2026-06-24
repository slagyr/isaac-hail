@wip
Feature: Hail threading and reply-to

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: New hail without thread or reply gets its own id as thread-id
    When a crew calls hail-send with:
      | frequency | {"band": "engineering-intercom", "session-tags": #{:project/warp-coil}} |
      | params    | {:dilithium-leak true}                                      |
    Then the result contains the assigned hail id "hail-123"
    And the created hail record has:
      | id        | hail-123 |
      | thread-id | hail-123 |
      | reply-to  | absent   |

  Scenario: Reply inherits thread-id from the replied-to hail
    Given an existing hail:
      | id        | hail-42            |
      | thread-id | dilithium-thread-7 |
    When a crew sends a hail with:
      | frequency | {"band": "engineering-intercom", "session-tags": #{:project/warp-coil}} |
      | params    | {:report "fracture confirmed"}                              |
      | reply-to  | hail-42                                                     |
      | (no thread-id)                                                          |
    Then the new hail record has:
      | thread-id | dilithium-thread-7 |
      | reply-to  | hail-42            |

  Scenario: The rendered prompt and params from a band hail (plus thread/reply info) are preserved in the delivered record
    Given an Isaac root at "target/test-state"
    Given config file "hail/engineering-intercom.md" containing:
      """
      {:session-tags #{:project/warp-coil}
       :reach     :one}
      Resonance climbing on {{coil}}, drift {{drift}}.
      """
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | params    | {:dilithium-leak true}         |
      | thread-id | dilithium-thread-7             |
      | reply-to  | hail-42                        |
    When the hail router ticks
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path           | value                                        |
      | hail.id        | hail-1                                       |
      | hail.prompt    | Resonance climbing on primary, drift 0.03.   |
      | hail.params    | {:dilithium-leak true}                       |
      | hail.thread-id | dilithium-thread-7                           |
      | hail.reply-to  | hail-42                                      |

  Scenario: Thread and reply-to are carried and usable by agents (with templated context)
    Given a hail in a conversation carries :thread-id and :reply-to
    When the hail is delivered
    Then the receiving agent's context (params or via hail_get) contains the thread-id and reply-to
    And the agent can use them when constructing follow-up hails

  Scenario: Follow-up hails on the same thread use their own band params to render the prompt while carrying thread-id and reply-to
    Given an existing hail:
      | id        | hail-42            |
      | thread-id | dilithium-thread-7 |
    When a crew sends a hail with:
      | frequency | {"band": "engineering-intercom", "session-tags": #{:project/warp-coil}} |
      | params    | {:coil "secondary", :drift 0.07}                              |
      | reply-to  | hail-42                                                     |
      | (no thread-id)                                                          |
    Then the new hail record has:
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |

  Scenario: An agent can use hail_get to retrieve a prior templated hail's rendered prompt and params, then send a follow-up on the thread using new params
    Given the EDN isaac file "hail/delivered/hail-1.edn" exists with:
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
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"tertiary\", :drift 0.11}' --reply-to hail-1"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-2.edn" contains:
      | path      | value                                        |
      | prompt    | Resonance climbing on tertiary, drift 0.11.  |
      | params    | {:coil "tertiary", :drift 0.11}              |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-1                                       |
