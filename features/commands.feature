Feature: Hail-delivered slash-like commands
  The turn queue does not reject hail-delivered input that looks like a
  slash command. A hail's expanded prompt becomes normal turn input for
  the receiving session.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a hail carrying an unknown command is delivered, not rejected
    Given the isaac EDN file "config/hail/prune-request.edn" exists with:
      | path         | value        |
      | session-tags | #{:wip}      |
    And the isaac file "config/hail/prune-request.md" exists with:
      """
      /prune dilithium-orchid
      """
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name       | crew       | tags    |
      | galley | hieronymus | #{:wip} |
    And the following model responses are queued:
      | type | content                | model  |
      | text | Acknowledged, Captain. | grover |
    When isaac is run with "hail send --band prune-request"
    Then the exit code is 0
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "galley" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | /prune dilithium-orchid |
      | message | assistant    | Acknowledged, Captain.  |
