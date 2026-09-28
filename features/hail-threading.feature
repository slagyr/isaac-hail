Feature: Hail threading and reply-to

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
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

  Scenario: New hail without thread or reply gets its own id as thread-id
    When isaac is run with "hail send --band engineering-intercom --params '{:dilithium-leak true}'"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value    |
      | id                | #turn-id |
      | origin.thread-id  | #turn-id |
      | origin.reply-to   |          |

  Scenario: Reply inherits thread-id from the replied-to hail
    Given the isaac EDN file "turns/hail-42.edn" exists with:
      | path   | value                                             |
      | id     | hail-42                                           |
      | state  | :finished                                         |
      | origin | {:source :hail :thread-id "dilithium-thread-7"}  |
    When isaac is run with "hail send --band engineering-intercom --params '{:report \"fracture confirmed\"}' --reply-to hail-42"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value               |
      | id                | #turn-id            |
      | origin.thread-id  | dilithium-thread-7  |
      | origin.reply-to   | hail-42             |

  Scenario: The rendered prompt and params from a band hail (plus thread/reply info) are preserved on the submitted turn
    Given the following model responses are queued:
      | type | content      | model  |
      | text | Acknowledged | grover |
    And the isaac EDN file "turns/hail-42.edn" exists with:
      | path   | value                                             |
      | id     | hail-42                                           |
      | state  | :finished                                         |
      | origin | {:source :hail :thread-id "dilithium-thread-7"}  |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03, :dilithium-leak true}' --reply-to hail-42"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                                 |
      | id                | #turn-id                                             |
      | input             | Resonance climbing on primary, drift 0.03.           |
      | origin.params     | {:coil "primary", :drift 0.03, :dilithium-leak true} |
      | origin.thread-id  | dilithium-thread-7                                   |
      | origin.reply-to   | hail-42                                               |
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content                            |
      | message | user         | Resonance climbing on primary, drift 0.03. |
      | message | assistant    | Acknowledged                                |

  Scenario: Thread and reply-to are carried and usable by agents (with templated context)
    Given the isaac EDN file "turns/hail-42.edn" exists with:
      | path   | value                                             |
      | id     | hail-42                                           |
      | state  | :finished                                         |
      | origin | {:source :hail :thread-id "dilithium-thread-7"}  |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"primary\", :drift 0.03}' --reply-to hail-42"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                   |
      | id                | #turn-id                               |
      | origin.thread-id  | dilithium-thread-7                     |
      | origin.reply-to   | hail-42                                |
      | preamble          | #"(?s).*dilithium-thread-7.*hail-42.*" |

  Scenario: Follow-up hails on the same thread use their own band params to render the prompt while carrying thread-id and reply-to
    Given the isaac EDN file "turns/hail-42.edn" exists with:
      | path   | value                                             |
      | id     | hail-42                                           |
      | state  | :finished                                         |
      | origin | {:source :hail :thread-id "dilithium-thread-7"}  |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"secondary\", :drift 0.07}' --reply-to hail-42"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":turn-id |
    And the turn Hail submitted has:
      | path             | value                                        |
      | id                | #turn-id                                    |
      | input             | Resonance climbing on secondary, drift 0.07. |
      | origin.params     | {:coil "secondary", :drift 0.07}            |
      | origin.thread-id  | dilithium-thread-7                          |
      | origin.reply-to   | hail-42                                      |

  Scenario: An agent can use turn__get to retrieve a prior hail turn's rendered prompt and params, then send a follow-up on the thread using new params
    Given the isaac EDN file "turns/hail-1.edn" exists with:
      | path   | value                                                                                                        |
      | id     | hail-1                                                                                                      |
      | state  | :finished                                                                                                   |
      | input  | Resonance climbing on secondary, drift 0.07.                                                               |
      | origin | {:source :hail :thread-id "dilithium-thread-7" :params {:coil "secondary" :drift 0.07} :reply-to "hail-42"} |
    When isaac is run with "turns show hail-1"
    Then the stdout matches:
      | #"(?s).*Resonance climbing on secondary, drift 0.07\..*dilithium-thread-7.*hail-42.*" |
    When isaac is run with "hail send --band engineering-intercom --params '{:coil \"tertiary\", :drift 0.11}' --reply-to hail-1"
    Then the exit code is 0
    And the stdout matches:
      | #"[a-z0-9]+":new-turn-id |
    And the turn Hail submitted has:
      | path             | value                                        |
      | id                | #new-turn-id                                |
      | input             | Resonance climbing on tertiary, drift 0.11. |
      | origin.params     | {:coil "tertiary", :drift 0.11}             |
      | origin.thread-id  | dilithium-thread-7                          |
      | origin.reply-to   | hail-1                                       |
