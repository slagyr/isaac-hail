Feature: Hail router
  The hail router ticks on the shared scheduler, reads raw hails from
  hail/pending/, and resolves each :frequency by enriching the hail IN
  PLACE with the resolved processing crew + session — keeping its id and
  filename. A reach-one hail moves to hail/deliveries/ named by its own
  hail id. A reach-all hail becomes a durable broadcast PARENT in
  hail/broadcasts/ that holds the :children ids, and the router mints one
  child delivery hail per matching session in hail/deliveries/ (each with
  its own id, a :source-hail back-ref, and the shared :thread-id). A
  reach-one pool of many is left unbound with a frozen :candidates list
  for the delivery worker to bind. Routing is fail-fast: a hail that
  cannot produce at least one delivery moves to hail/undeliverable/ with a
  :reason. After a tick every processed hail has left pending/. The
  delivery worker (separate bean) consumes hail/deliveries/.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a reach-one band matching exactly one session binds immediately
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
      | reach        | :one              |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name        | crew        | tags              |
      | engine-room | bartholomew | #{:role/engineer} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | payload   | {:dilithium-leak true}         |
      | from      | :cli                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path      | value                          | #comment                      |
      | id        | hail-1                         | same id, enriched in place    |
      | frequency | {:band "engineering-intercom"} |                               |
      | payload   | {:dilithium-leak true}         |                               |
      | crew      | bartholomew                    | only one engineer → bound now |
      | session   | engine-room                    |                               |

  Scenario: a reach-one tag pool of many is left unbound with frozen candidates
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     | tags             |
      | bridge      | atticus  | #{:role/command} |
      | first-watch | cordelia | #{:role/command} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                            |
      | id        | hail-1                           |
      | frequency | {:session-tags #{:role/command}} |
      | reach     | :one                             |
      | prompt    | Status report?                   |
      | from      | :cli                             |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path       | value                                                                       | #comment             |
      | id         | hail-1                                                                      |                      |
      | crew       |                                                                             | unbound — nil        |
      | session    |                                                                             | unbound — nil        |
      | candidates | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] | frozen pool snapshot |

  Scenario: a frequency :crew selects sessions of that crew
    Given the following sessions exist:
      | name         | crew    |
      | agile-voyage | main    |
      | side-job     | marvin  |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value              |
      | id        | hail-1             |
      | frequency | {:crew "main"}     |
      | reach     | :one               |
      | prompt    | Work the backlog.  |
      | from      | :cli               |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value        | #comment                         |
      | id      | hail-1       |                                  |
      | crew    | main         | processing crew = session :crew |
      | session | agile-voyage | only main-crew session matched   |

  Scenario: a direct session frequency binds to that exact session only
    Given the isaac EDN file "config/crew/mavis.edn" exists with:
      | path  | value              |
      | model | grover             |
      | tags  | #{:role/navigator} |
    And the following sessions exist:
      | name           | crew  |
      | charted-course | mavis |
      | side-quest     | mavis |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                        |
      | id        | hail-1                       |
      | frequency | {:session [:charted-course]} |
      | prompt    | Adjust bearing 12 degrees.   |
      | from      | :cli                         |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value          | #comment             |
      | id      | hail-1         |                      |
      | crew    | mavis          |                      |
      | session | charted-course | the targeted session |
    And the isaac file "hail/broadcasts/hail-1.edn" does not exist

  Scenario: reach :all becomes a broadcast parent plus one child delivery per matching session
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     | tags             |
      | bridge      | atticus  | #{:role/command} |
      | first-watch | cordelia | #{:role/command} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                            |
      | id        | hail-1                           |
      | frequency | {:session-tags #{:role/command}} |
      | reach     | :all                             |
      | prompt    | Red alert!                       |
      | from      | :cli                             |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/broadcasts/hail-1.edn" EDN contains:
      | path | value  | #comment                 |
      | id   | hail-1 | durable broadcast parent |
    And broadcast "hail-1" children are distinct bare short-uuids
    And child delivery for session bridge EDN contains:
      | path        | value   | #comment                         |
      | id          | <short-uuid> | child delivery, own id      |
      | source-hail | hail-1  | back-ref to the broadcast parent |
      | crew        | :atticus |                                 |
      | session     | :bridge | children sorted by session       |
    And child delivery for session first-watch EDN contains:
      | path        | value       |
      | id          | <short-uuid> |
      | source-hail | hail-1      |
      | crew        | :cordelia   |
      | session     | :first-watch |
    And delivery hail count is 2

  Scenario: combined band and session-tag intersect to one bound delivery
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
      | reach        | :one              |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name           | crew        | tags                                 |
      | engine-room    | bartholomew | #{:role/engineer}                    |
      | coil-tinkering | bartholomew | #{:role/engineer :project/warp-coil} |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                                                              |
      | id        | hail-1                                                             |
      | frequency | {:band "engineering-intercom" :session-tags #{:project/warp-coil}} |
      | payload   | {:resonance-drift 0.03}                                            |
      | from      | :cli                                                               |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value          | #comment                                   |
      | id      | hail-1         |                                            |
      | crew    | bartholomew    |                                            |
      | session | coil-tinkering | warp-coil session matched, engine-room not |

  Scenario: a band :crew selects sessions whose crew matches
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path | value        |
      | crew | "bartholomew" |
      | reach | :one        |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
      | greenhouse  | hieronymus  |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | payload   | {:n 1}                         |
      | from      | :cli                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value       | #comment                    |
      | id      | hail-1      |                             |
      | crew    | bartholomew | band :crew session selector |
      | session | engine-room | hieronymus not selected     |

  Scenario: a frequency with no session selector moves the hail to undeliverable
    Given the following sessions exist:
      | name        | crew |
      | engine-room | main |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value           |
      | id        | hail-1          |
      | frequency | {:reach :one}   |
      | prompt    | Orphan reach.   |
      | from      | :cli            |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value          | #comment                          |
      | id     | hail-1         |                                   |
      | reason | :no-recipients | absent selectors must not match-all |

  Scenario: processing crew comes from the matched session
    And the following sessions exist:
      | name        | crew |
      | engine-room | main |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                      |
      | id        | hail-1                     |
      | frequency | {:session [:engine-room]}  |
      | prompt    | Check the gauges.          |
      | from      | :cli                       |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value       | #comment                       |
      | id      | hail-1      |                                |
      | crew    | main        | session :crew, else cfg default  |
      | session | engine-room |                                |

  Scenario: an unknown band moves the hail to undeliverable
    Given the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                  | #comment                               |
      | id        | hail-1                 |                                        |
      | frequency | {:band "phantom-band"} | no config/hail/phantom-band.edn exists |
      | payload   | {:n 1}                 |                                        |
      | from      | :cli                   |                                        |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path      | value                  | #comment                  |
      | id        | hail-1                 | same id, :reason added    |
      | frequency | {:band "phantom-band"} |                           |
      | reason    | :unknown-band          | why it couldn't be routed |

  Scenario: a reach-one band with no matching session moves the hail to undeliverable
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path         | value             |
      | session-tags | #{:role/engineer} |
      | reach        | :one              |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             | #comment                   |
      | model | grover            |                            |
      | tags  | #{:role/botanist} | no engineer-tagged session |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | payload   | {:n 1}                         |
      | from      | :cli                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value          | #comment                         |
      | id     | hail-1         |                                  |
      | reason | :no-recipients | band exists, no engineer matched |

  Scenario: reach :all matching zero sessions moves the hail to undeliverable
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/botanist} |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path      | value                            | #comment                  |
      | id        | hail-1                           |                           |
      | frequency | {:session-tags #{:role/command}} | no command-tagged session |
      | reach     | :all                             |                           |
      | prompt    | All hands!                       |                           |
      | from      | :cli                             |                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/broadcasts/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" EDN contains:
      | path   | value          | #comment                    |
      | id     | hail-1         |                             |
      | reason | :no-recipients | snapshot matched no session |

  Scenario: the hail router tick is registered with the shared scheduler
    When the Isaac system is started
    Then the scheduled tasks include:
      | id         | trigger.kind | trigger.ms |
      | hail/route | interval     | 1000       |

  # --- Conform :frequencies onto the shared session selector (isaac-c58s) ---
  # :frequencies holds the same flat map the prompt command builds (select keys
  # + :with-* override keys). --prefer orders the frozen reach-one candidates;
  # --with-crew overrides the processing crew.

  @wip
  Scenario: --prefer orders the frozen candidates for a reach-one multi-match
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     | tags             | updated-at          |
      | bridge      | atticus  | #{:role/command} | 2026-04-12T15:00:00 |
      | first-watch | cordelia | #{:role/command} | 2026-04-10T10:00:00 |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                     | value            |
      | id                       | hail-1           |
      | frequencies.session-tags | #{:role/command} |
      | frequencies.reach        | :one             |
      | frequencies.prefer       | :oldest          |
      | prompt                   | Status report?   |
      | from                     | :cli             |
    When the hail router ticks
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path       | value                                                                       | #comment                |
      | candidates | [{:crew :cordelia :session :first-watch} {:crew :atticus :session :bridge}] | oldest-first by :prefer |

  @wip
  Scenario: --with-crew overrides the processing crew
    Given the following sessions exist:
      | name        | crew |
      | engine-room | main |
    And the isaac EDN file "config/crew/navigator.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file hail/pending/hail-1.edn exists with:
      | path                  | value             |
      | id                    | hail-1            |
      | frequencies.session   | [:engine-room]    |
      | frequencies.with-crew | :navigator        |
      | prompt                | Check the gauges. |
      | from                  | :cli              |
    When the hail router ticks
    Then the isaac file "hail/deliveries/hail-1.edn" EDN contains:
      | path    | value       | #comment                                 |
      | id      | hail-1      |                                          |
      | crew    | navigator   | :with-crew override beats session's main |
      | session | engine-room | selected by :session, unchanged          |
