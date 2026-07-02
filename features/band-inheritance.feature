@wip
Feature: Hail band inheritance via base template bands
  A band may declare :base — another band file it composes over — so shared
  coordinates (bean-repo, notification-comm, ...) live once per project in a
  template band instead of being duplicated across every band.

  Merge semantics (child over base), one level deep:
    (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b)) base child)
  — map-valued keys (like :data) merge key-wise with the child winning per
  key; scalar/vector values are replaced wholesale by the child. Bodies:
  the child's .md wins; absent/blank child body falls back to the base's.
  Base chains resolve transitively with cycle protection. Band files whose
  name starts with "_" are templates: they participate in inheritance but
  are not addressable.

  (Bean: isaac-8ywz, blocked by isaac-iz3a. Design settled with Micah
  2026-07-02.)

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: A child band inherits session-tags and data from its base template
    Given default Grover setup
    And the isaac EDN file "config/hail/_engineering-template.edn" exists with:
      | path         | value                                        |
      | session-tags | #{:project/warp-coil}                        |
      | reach        | :one                                         |
      | data         | {:bean-repo "git@example.com:acme/warp.git"} |
    And the isaac EDN file "config/hail/engineering-verify.edn" exists with:
      | path | value                             |
      | base | _engineering-template             |
      | data | {:work-hail "engineering-work"}   |
    And the isaac file "config/hail/engineering-verify.md" exists with:
      """
      Verify the coil work.
      """
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                 |
      | model | grover                |
      | tags  | #{:project/warp-coil} |
    And the following sessions exist:
      | name        | crew        | tags                  |
      | engine-room | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content | model  |
      | text | On it.  | grover |
    When the config is loaded
    When isaac is run with "hail send --band engineering-verify"
    Then the exit code is 0
    When the hail router ticks
    And the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the hail turn on session "engine-room" has a system preamble matching:
      | pattern                                |
      | #"(?s).*bean-repo.*acme/warp\.git.*"   |
      | #"(?s).*work-hail.*engineering-work.*" |
    And session "engine-room" has transcript matching:
      | message.role | message.content                    |
      | user         | #"(?s).*Verify the coil work\..*" |

  Scenario: A child data key overrides the same key in the base, base-only keys survive
    Given the isaac EDN file "config/hail/_engineering-template.edn" exists with:
      | path         | value                                                     |
      | session-tags | #{:project/warp-coil}                                     |
      | reach        | :one                                                      |
      | data         | {:notification-channel "pub", :bean-repo "git@x:a/b.git"} |
    And the isaac EDN file "config/hail/engineering-work.edn" exists with:
      | path | value                            |
      | base | _engineering-template            |
      | data | {:notification-channel "engine"} |
    And the isaac file "config/hail/engineering-work.md" exists with:
      """
      Work the coil.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-work"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path | value                                                        |
      | data | {:notification-channel "engine", :bean-repo "git@x:a/b.git"} |

  Scenario: A child without a body inherits the base band's body as its template
    Given the isaac EDN file "config/hail/_engineering-template.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    And the isaac file "config/hail/_engineering-template.md" exists with:
      """
      Attend to {{task}} in the engine room.
      """
    And the isaac EDN file "config/hail/engineering-work.edn" exists with:
      | path | value                 |
      | base | _engineering-template |
    When the config is loaded
    When isaac is run with "hail send --band engineering-work --params '{:task \"the coil\"}'"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path   | value                                    |
      | prompt | Attend to the coil in the engine room.   |

  Scenario: Base chains resolve transitively
    Given the isaac EDN file "config/hail/_fleet-template.edn" exists with:
      | path | value                            |
      | data | {:fleet "seventh", :deck "one"}  |
    And the isaac EDN file "config/hail/_engineering-template.edn" exists with:
      | path         | value                 |
      | base         | _fleet-template       |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
      | data         | {:deck "engineering"} |
    And the isaac EDN file "config/hail/engineering-work.edn" exists with:
      | path | value                 |
      | base | _engineering-template |
    And the isaac file "config/hail/engineering-work.md" exists with:
      """
      Work the coil.
      """
    When the config is loaded
    When isaac is run with "hail send --band engineering-work"
    Then the exit code is 0
    And pending hail 1 EDN contains:
      | path | value                                |
      | data | {:fleet "seventh", :deck "engineering"} |

  Scenario: A base cycle is a clear error, not a hang
    Given config file "hail/_alpha.edn" containing:
      """
      {:base "_beta"}
      """
    And config file "hail/_beta.edn" containing:
      """
      {:base "_alpha"}
      """
    When isaac is run with "config validate"
    Then the stderr contains "cycle"
    And the exit code is 1

  Scenario: A missing base reference is a clear error
    Given config file "hail/orphan.edn" containing:
      """
      {:base "_no-such-template"
       :session-tags [:project/chess]
       :reach :one}
      """
    When isaac is run with "config validate"
    Then the stderr contains "base"
    And the exit code is 1

  Scenario: Template bands are not addressable
    Given the isaac EDN file "config/hail/_engineering-template.edn" exists with:
      | path         | value                 |
      | session-tags | #{:project/warp-coil} |
      | reach        | :one                  |
    When the config is loaded
    When isaac is run with "hail send --band _engineering-template --prompt 'hello'"
    Then the exit code is 1
    And the stderr contains "template"
