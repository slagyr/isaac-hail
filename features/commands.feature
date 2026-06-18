Feature: Hail-delivered slash-like commands
  Delivery does not reject hail content that looks like a slash
  command. The delivered hail becomes normal turn input for the
  receiving session.

  Background:
    Given default Grover setup

  Scenario: a hail carrying an unknown command is delivered, not rejected
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the following model responses are queued:
      | type | content                | model  |
      | text | Acknowledged, Captain. | grover |
    And the isaac EDN file hail/deliveries/delivery-1.edn exists with:
      | path        | value                   |
      | id          | delivery-1              |
      | hail.id     | hail-1                  |
      | hail.prompt | /prune dilithium-orchid |
      | crew        | hieronymus              |
      | session     | greenhouse              |
      | attempts    | 0                       |
    When the hail delivery worker ticks
    And the turn ends on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | /prune dilithium-orchid |
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the isaac file "hail/delivered/delivery-1.edn" EDN contains:
      | path | value      |
      | id   | delivery-1 |
