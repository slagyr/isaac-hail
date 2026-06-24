Feature: Hail get and search

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: hail_get tool can fetch a hail by id from any subdir
    Given the EDN isaac file "hail/delivered/hail-42.edn" exists with:
      | path      | value                  |
      | id        | hail-42                |
      | prompt    | context                |
      | params    | {:n 1}                 |
      | thread-id | thread-1               |
      | reply-to  | hail-parent            |
      | sent-at   | 2026-06-23T12:00:00Z   |
    When an agent calls the hail_get tool with id "hail-42"
    Then it returns the full hail record including prompt, params, thread-id, reply-to, sent-at

  Scenario: Searching uses directory scan (no index required)
    Given the hail directory contains sub-directories pending, deliveries, inflight, delivered, failed, undeliverable
    When hail_get searches for an arbitrary id
    Then it locates the matching *.edn file by walking the sub-directories
    And no index file is read or required

  Scenario: hail_get on a hail from a templated band returns the rendered prompt, the params, thread-id, reply-to, sent-at (from any subdir)
    Given the EDN isaac file "hail/delivered/hail-1.edn" exists with:
      | path      | value                                        |
      | id        | hail-1                                       |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
      | sent-at   | 2026-06-23T12:00:00Z                         |
    When an agent calls the hail_get tool with id "hail-1"
    Then it returns the hail record containing:
      | path      | value                                        |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
      | sent-at   | 2026-06-23T12:00:00Z                         |

  Scenario: The hail_get tool returns the hail record with the rendered prompt, the params, thread-id, reply-to, and sent-at, allowing agents to access full prior context
    Given the EDN isaac file "hail/delivered/hail-1.edn" exists with:
      | path      | value                                        |
      | id        | hail-1                                       |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
      | sent-at   | 2026-06-23T12:00:00Z                         |
    When an agent calls the hail_get tool with id "hail-1"
    Then it returns the hail record containing:
      | path      | value                                        |
      | prompt    | Resonance climbing on secondary, drift 0.07. |
      | params    | {:coil "secondary", :drift 0.07}             |
      | thread-id | dilithium-thread-7                           |
      | reply-to  | hail-42                                      |
      | sent-at   | 2026-06-23T12:00:00Z                         |

  Scenario: Searching uses directory scan for templated band hails (with rendered prompt and params)
    Given the hail directory contains sub-directories pending, deliveries, inflight, delivered, failed, undeliverable
    And hails from templated bands exist with rendered prompts and params in those dirs
    When hail_get searches for an arbitrary id
    Then it locates the matching *.edn file by walking the sub-directories
    And no index file is read or required
