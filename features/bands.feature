Feature: Hail bands declared in config
  Bands are declared via files under ~/.isaac/config/hail/<name>.edn
  with optional .md companions for the prompt. The config schema
  validates each band's shape; bad declarations error at validate
  time.

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
       :spawn-session true}
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

  Scenario: config validate rejects :crew-tags as retired
    Given config file "hail/stale.edn" containing:
      """
      {:crew-tags [:role/worker]
       :session-tags [:project/chess]
       :reach :one}
      """
    When isaac is run with "config validate"
    Then the stderr contains "crew-tags"
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