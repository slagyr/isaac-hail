Feature: Hailed turns say who sent them and for whom
  Every turn records who started it (:from) and on whose behalf (:for).
  A hail is one more way to start a turn, so the turn it queues must carry
  both. A crew that hails from inside a turn is the sender, and the hail
  stays the work of whoever that crew was working for. A hail that arrives
  over HTTP is from the principal that sent it; one typed at the command
  line is from the CLI. Neither of those is on anyone's behalf.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And config:
      | http.auth.token | secret123 |
    And the isaac EDN file "config/hail/harbor-watch.edn" exists with:
      | path         | value             |
      | session-tags | #{:project/harbor} |
    And the isaac file "config/hail/harbor-watch.md" exists with:
      """
      Walk the harbor wall.
      """
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/lookout.edn" exists with:
      | path  | value              |
      | model | grover             |
      | tags  | #{:project/harbor} |
    And the following sessions exist:
      | name       | crew    | tags               |
      | mooring    | ketch   |                    |
      | crows-nest | lookout | #{:project/harbor} |

  Scenario: a crew's hail is from the crew, for the person it was working for
    Given the crew "ketch" allows tools: "hail-send"
    And the following model responses are queued:
      | model | tool_call | arguments                |
      | echo  | hail-send | {"band": "harbor-watch"} |
      | model | type      | content                  |
      | echo  | text      | Lookout is on it.        |
    When the user sends "is the wall sound?" on session "mooring" via memory comm as handle "logbook/cordelia-7"
    Then the turn Hail submitted has:
      | path        | value        |
      | origin.band | harbor-watch |
      | from.kind   | :crew        |
      | from.id     | "ketch"      |
      | for.kind    | :handle      |
      | for.comm    | :logbook     |
      | for.id      | "cordelia-7" |

  Scenario: a crew working for nobody hails for nobody
    Given the crew "ketch" allows tools: "hail-send"
    And the following model responses are queued:
      | model | tool_call | arguments                |
      | echo  | hail-send | {"band": "harbor-watch"} |
      | model | type      | content                  |
      | echo  | text      | Lookout is on it.        |
    When the user sends "walk the wall" on session "mooring"
    Then the turn Hail submitted has:
      | path        | value        |
      | origin.band | harbor-watch |
      | from.kind   | :crew        |
      | from.id     | "ketch"      |
      | for         |              |

  Scenario: a hail over HTTP is from the principal that sent it
    Given principal "tender" is configured with secret "tender-secret" and scopes "hail/send"
    When a POST request is made to "/hail/send":
      | key                  | value                                    |
      | header.Content-Type  | application/json                         |
      | header.Authorization | Bearer tender-secret                     |
      | body                 | {"frequencies": {"band": "harbor-watch"}} |
    Then the response status is 201
    And the turn Hail submitted has:
      | path        | value        |
      | origin.band | harbor-watch |
      | from.kind   | :http        |
      | from.id     | "tender"     |
      | for         |              |

  Scenario: a hail sent with the plain server token is from admin
    When a POST request is made to "/hail/send":
      | key                  | value                                    |
      | header.Content-Type  | application/json                         |
      | header.Authorization | Bearer secret123                         |
      | body                 | {"frequencies": {"band": "harbor-watch"}} |
    Then the response status is 201
    And the turn Hail submitted has:
      | path        | value        |
      | origin.band | harbor-watch |
      | from.kind   | :http        |
      | from.id     | "admin"      |
      | for         |              |

  Scenario: a hail from the command line is from the CLI
    When isaac is run with "hail send --band harbor-watch"
    Then the exit code is 0
    And the turn Hail submitted has:
      | path        | value        |
      | origin.band | harbor-watch |
      | from.kind   | :cli         |
      | from.id     |              |
      | for         |              |
