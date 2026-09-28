Feature: Hail send — direct addressing flags
  Beyond `--band`, `isaac hail send` accepts `--crew` (session selector in
  :frequencies), `--session`, `--session-tag`, and `--from-json`. Routing
  selectors live in `:frequencies`. `--dry-run` expands and prints the
  submission without queuing a turn.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: --crew populates :crew in the frequency address map
    When isaac is run with "hail send --crew marvin --prompt 'Heads up' --session-tag wip --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                                  |
      | frequencies | {:crew "marvin" :session-tags #{:wip}} |
      | input       | Heads up                               |
      | origin.from | :cli                                   |

  Scenario: --session populates :session in the address map
    When isaac is run with "hail send --session tidy-cavern --prompt 'wake up' --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                     |
      | frequencies | {:session [:tidy-cavern]} |
      | input       | wake up                   |
      | origin.from | :cli                      |

  Scenario: --session-tag populates :session-tags (repeatable AND-set)
    When isaac is run with "hail send --session-tag project/chess --session-tag wip --prompt 'go' --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                                  |
      | frequencies | {:session-tags #{:project/chess :wip}} |
      | input       | go                                     |
      | origin.from | :cli                                   |

  Scenario: combining --crew with --session-tag sets both selectors in :frequencies
    When isaac is run with "hail send --crew marvin --session-tag project/chess --prompt 'go' --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                                            |
      | frequencies | {:crew "marvin" :session-tags #{:project/chess}} |
      | input       | go                                               |
      | origin.from | :cli                                             |

  Scenario: --from-json reads the whole hail from stdin as JSON
    Given stdin is:
      """
      {"frequencies": {"band": "bean-pickup"}, "params": {"n": 1}}
      """
    When isaac is run with "hail send - --from-json --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path          | value                 |
      | frequencies   | {:band "bean-pickup"} |
      | origin.params | {:n 1}                |
      | origin.from   | :cli                  |

  Scenario: bare - reads the whole hail from stdin as EDN
    Given stdin is:
      """
      {:frequencies {:crew :marvin
                     :session-tags #{:project/chess}}
       :prompt      "go"
       :params      {:n 1}}
      """
    When isaac is run with "hail send - --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path          | value                                            |
      | frequencies   | {:crew :marvin :session-tags #{:project/chess}}  |
      | input         | go                                               |
      | origin.params | {:n 1}                                           |
      | origin.from   | :cli                                             |

  Scenario: send rejects a frequency with no session selector
    When isaac is run with "hail send --prompt 'orphan'"
    Then the stderr contains "addressing"
    And the exit code is 1

  Scenario: direct addressing without --prompt errors clearly
    When isaac is run with "hail send --session-tag wip"
    Then the stderr contains "prompt"
    And the exit code is 1

  Scenario Outline: keyword flags accept a leading colon (isaac-k0xm)
    When isaac is run with "hail send --session-tag <tag> --prompt 'hi' --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path                     | value           |
      | frequencies.session-tags | #{:project/foo} |

    Examples:
      | tag          |
      | :project/foo |
      | project/foo  |

  Scenario: a keyword flag value that cannot read back is refused naming the flag (isaac-k0xm)
    When isaac is run with "hail send --session-tag :::x --prompt 'hi' --dry-run"
    Then the stderr contains "--session-tag"
    And the exit code is 1
    When isaac is run with "turns list --all"
    Then the stdout is empty

  Scenario: --crew and --session strip a leading colon too (isaac-k0xm)
    When isaac is run with "hail send --crew :yopp --session :abc --prompt go --dry-run"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value                          |
      | frequencies | {:crew "yopp" :session [:abc]} |
