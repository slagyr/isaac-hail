Feature: Hail bands declared in config
  Bands are declared via files under ~/.isaac/config/hail/<name>.edn
  (or single <name>.md with YAML frontmatter + prompt body, like crews).
  The config schema validates each band's shape; bad declarations error
  at validate time.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: config validate accepts a valid band declaration
    Given config file "crew/ops.edn" containing:
      """
      {:model :grover}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "echo" :provider :grover :context-window 32768}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    And config file "hail/bean-pickup.edn" containing:
      """
      {:crew         "ops"
       :session-tags [:project/chess]
       :reach        :one
       :create :if-missing}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  Scenario: config validate rejects a band with an invalid :reach
    Given config file "hail/bogus.edn" containing:
      """
      {:session-tags [:project/chess]
       :reach        :many}
      """
    When isaac is run with "config validate"
    Then the stderr contains "reach"
    And the exit code is 1

  Scenario: config validate rejects :crew as a seq
    Given config file "hail/stale.edn" containing:
      """
      {:crew [:ops]
       :session-tags [:project/chess]
       :reach :one}
      """
    When isaac is run with "config validate"
    Then the stderr contains "crew"
    And the exit code is 1

  Scenario: config validate accepts a band with data frontmatter
    Given config file "crew/ops.edn" containing:
      """
      {:model :grover}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "echo" :provider :grover :context-window 32768}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    And config file "hail/bean-pickup.md" containing:
      """
      ---
      session-tags:
        - :project/chess
      reach: :one
      data:
        bean-repo: isaac
        bean-id: "{{bean-id}}"
      ---
      Pick up the bean.
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  Scenario: config validate accepts a band defined as a single .md with frontmatter
    Given config file "crew/ops.edn" containing:
      """
      {:model :grover}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "echo" :provider :grover :context-window 32768}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    And config file "hail/bean-pickup.md" containing:
      """
      ---
      crew: ops
      session-tags:
        - :project/chess
      reach: :one
      ---
      Pick up the beans in the galley.
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the stdout does not contain "dangling"
    And the exit code is 0

  Scenario: config validate schema-checks a frontmatter band (type conflict rejected)
    Given config file "hail/bad.md" containing:
      """
      ---
      session-tags:
        - :project/chess
      reach: 5
      ---
      Bad reach type.
      """
    When isaac is run with "config validate"
    Then the stderr contains "reach"
    And the exit code is 1

  Scenario: a body-only .md still works as the prompt companion for an .edn band
    Given config file "crew/ops.edn" containing:
      """
      {:model :grover}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "echo" :provider :grover :context-window 32768}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    And config file "hail/relay.edn" containing:
      """
      {:crew         "ops"
       :session-tags [:project/chess]
       :reach        :one}
      """
    And config file "hail/relay.md" containing:
      """
      Relay the message to the bridge.
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the stdout does not contain "dangling"
    And the exit code is 0