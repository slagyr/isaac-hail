Feature: Hail band inheritance from template bands
  Bands may declare base: <template-name> to inherit shared frontmatter and prompt
  body from another band file. Template bands (names starting with _) are not
  addressable via hail send --band.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: A child band inherits merged frontmatter from its base template
    Given the isaac file "config/hail/_isaac-template.md" exists with:
      """
      ---
      session-tags:
        - :isaac
      reach: :one
      data:
        bean-repo: isaac
        notification-comm: longwave
      ---
      """
    And the isaac file "config/hail/isaac-verify.md" exists with:
      """
      ---
      base: _isaac-template
      crew: perceptor
      data:
        plan-hail: isaac-plan
        work-hail: isaac-work
      ---
      Verify the bean work.
      """
    And the isaac EDN file "config/crew/perceptor.edn" exists with:
      | path  | value        |
      | model | grover       |
      | tags  | #{:isaac}    |
    And the following sessions exist:
      | name     | crew      | tags       |
      | verify-1 | perceptor | #{:isaac}  |
    And the following model responses are queued:
      | type | content | model  |
      | text | Done.   | grover |
    When the config is loaded
    When isaac is run with "hail send --band isaac-verify"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "verify-1"
    Then the hail turn on session "verify-1" has a system preamble matching:
      | pattern                              |
      | #"(?s).*Data:.*bean-repo.*isaac.*"  |
      | #"(?s).*plan-hail.*isaac-plan.*"     |
    And session "verify-1" has transcript matching:
      | message.role | message.content                 |
      | user         | #"(?s).*Verify the bean work.*" |

  Scenario: A blank child body inherits the base template body as prompt
    Given the isaac file "config/hail/_relay-template.md" exists with:
      """
      ---
      session-tags:
        - :project/chess
      reach: :one
      ---
      Relay from the template.
      """
    And the isaac file "config/hail/relay-child.md" exists with:
      """
      ---
      base: _relay-template
      crew: bartholomew
      ---
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:project/chess} |
    And the following sessions exist:
      | name        | crew        | tags              |
      | engine-room | bartholomew | #{:project/chess} |
    And the following model responses are queued:
      | type | content | model  |
      | text | Copy.   | grover |
    When the config is loaded
    When isaac is run with "hail send --band relay-child"
    Then the exit code is 0
    And the sole pending hail EDN contains:
      | path   | value                    |
      | prompt | Relay from the template. |
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then session "engine-room" has transcript matching:
      | message.role | message.content                    |
      | user         | #"(?s).*Relay from the template.*" |

  Scenario: Hailing a template band is rejected
    Given the isaac file "config/hail/_secret-template.md" exists with:
      """
      ---
      session-tags:
        - :isaac
      reach: :one
      ---
      Template only.
      """
    When the config is loaded
    When isaac is run with "hail send --band _secret-template"
    Then the exit code is 1
    And the stderr contains "template"

  Scenario: config validate reports a missing base reference
    Given the isaac file "config/hail/orphan.md" exists with:
      """
      ---
      base: _missing-template
      crew: bartholomew
      ---
      Orphan band.
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "missing"

  Scenario: config validate reports a base cycle
    Given the isaac file "config/hail/cycle-a.md" exists with:
      """
      ---
      base: cycle-b
      crew: bartholomew
      ---
      A
      """
    And the isaac file "config/hail/cycle-b.md" exists with:
      """
      ---
      base: cycle-a
      session-tags:
        - :isaac
      ---
      B
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "cycle"