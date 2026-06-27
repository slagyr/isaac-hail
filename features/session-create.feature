Feature: Hail-driven session create (get-or-create)
  A create-enabled reach-one hail uses get-or-create for its target session.
  The :create flag is the descriptive/prescriptive toggle for the
  frequencies' tags:

    :create :never (default) - tags are a read-only FILTER over existing
                                    sessions. No match -> undeliverable. Nothing
                                    is ever created.
    :create :if-missing            - MATCH-OR-CREATE. An existing matching
                                    session -> deliver to it; none -> create one
                                    under the resolved processing crew, apply the
                                    hail's :session-tags, deliver. (One-line:
                                    ":create :if-missing = create the addressed
                                    session if it doesn't exist.")

  Two phases:
  - Router (features/hail/router.feature): a create-enabled reach-one with no
    matching session enriches the hail in place with the resolved processing
    crew (:crew from hail -> band -> cfg-default/main) and an unbound session,
    keeping its id as hail/deliveries/<hail-id>.edn.
  - Delivery worker (features/hail/delivery.feature): treats a create-enabled
    delivery as live get-or-create each tick. An existing matching session
    that is idle -> bind; busy -> wait (never a sibling — preserves the
    crew's context); none -> create under the delivery's resolved :crew,
    tagging the session with the hail's :session-tags and marking
    :origin {:kind :hail ...}.

  Create is reach-one only; :reach :all never creates. Default :create is
  :never.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: create-enabled reach-one with no matching session yields a create delivery
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                    | value                 |
      | id                      | hail-1                |
      | frequencies.session-tags  | #{:project/warp-coil} |
      | frequencies.reach         | :one                  |
      | frequencies.create | :if-missing                  |
      | prompt                  | Resonance climbing.   |
      | from                    | :cli                  |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path                    | value  | #comment                              |
      | id                      | hail-1 | same id, enriched in place            |
      | frequencies.create | :if-missing   | create-eligible, unbound              |
      | crew                    | main   | resolved at router time (cfg default) |
      | session                 |        | nil                                   |

  Scenario: without create, no matching session is undeliverable
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                   | value                 |
      | id                     | hail-1                |
      | frequencies.session-tags | #{:project/warp-coil} |
      | frequencies.reach        | :one                  |
      | prompt                 | Resonance climbing.   |
      | from                   | :cli                  |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value          | #comment                        |
      | id     | hail-1         |                                 |
      | reason | :no-recipients | create off — no existing session |

  Scenario: a create delivery with no existing session creates a tagged session and dispatches
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                    | value                 |
      | id                      | hail-1                |
      | crew                    | :bartholomew          |
      | frequencies.session-tags  | #{:project/warp-coil} |
      | frequencies.reach         | :one                  |
      | frequencies.create | :if-missing                  |
      | prompt                  | Resonance climbing.   |
      | attempts                | 0                     |
    When the hail delivery worker ticks
    And the turn ends on session "session-1"
    Then the following sessions match:
      | id        | crew        | tags                  | origin.kind |
      | session-1 | bartholomew | #{:project/warp-coil} | hail        |
    And session "session-1" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |
    And the isaac file "hail/deliveries/hail-1.edn" does not exist
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path    | value       |
      | crew    | bartholomew |
      | session | session-1   |

  Scenario: a create delivery binds an existing matching session instead of spawning
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                    | value                 |
      | id                      | hail-1                |
      | crew                    | :main                 |
      | frequencies.session-tags  | #{:project/warp-coil} |
      | frequencies.reach         | :one                  |
      | frequencies.create | :if-missing                  |
      | prompt                  | Resonance climbing.   |
      | attempts                | 0                     |
    When the hail delivery worker ticks
    And the turn ends on session "coil-work"
    Then session "session-1" does not exist
    And session "coil-work" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |
    And the isaac file "hail/delivered/hail-1.edn" EDN contains:
      | path    | value     |
      | session | coil-work |

  Scenario: a create delivery whose only matching session is in flight waits, no sibling
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value             |
      | model         | grover            |
      | tags          | #{:role/engineer} |
      | max-in-flight | 2                 |
    And the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And session "coil-work" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                    | value                 |
      | id                      | hail-1                |
      | crew                    | :main                 |
      | frequencies.session-tags  | #{:project/warp-coil} |
      | frequencies.reach         | :one                  |
      | frequencies.create | :if-missing                  |
      | prompt                  | Resonance climbing.   |
      | attempts                | 0                     |
    When the hail delivery worker ticks
    Then session "session-1" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                                            |
      | id       | hail-1 | matching session busy — wait, don't spawn a sibling |
      | attempts | 0      |                                                     |

  Scenario: a create delivery waits when the resolved processing crew is at capacity
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value             |
      | model         | grover            |
      | tags          | #{:role/engineer} |
      | max-in-flight | 1                 |
    And the following sessions exist:
      | name       | crew        |
      | other-work | bartholomew |
    And session "other-work" is in flight
    And the isaac EDN file hail/deliveries/hail-1.edn exists with:
      | path                    | value                 |
      | id                      | hail-1                |
      | crew                    | :bartholomew          |
      | frequencies.session-tags  | #{:project/warp-coil} |
      | frequencies.reach         | :one                  |
      | frequencies.create | :if-missing                  |
      | prompt                  | Resonance climbing.   |
      | attempts                | 0                     |
    When the hail delivery worker ticks
    Then session "session-1" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path     | value  | #comment                                 |
      | id       | hail-1 | crew at capacity — can't create yet, wait |
      | attempts | 0      |                                          |
