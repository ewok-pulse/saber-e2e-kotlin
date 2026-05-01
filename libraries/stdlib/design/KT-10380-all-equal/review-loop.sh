#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Automated review loop for KT-10380 (allEqual API) implementation
# Codex reviews the implementation -> Claude validates & applies fixes
# Usage: ./review-loop.sh [--force-regen] <from> <to(included)>
# ============================================================

# The implementation begins at this commit (inclusive).
# The reviewer sees `git diff ${START_COMMIT}^..HEAD`.
START_COMMIT="a204bd5063c60d21c42aad0a03d3411e776bfacc"

# Path prefixes Claude is allowed to modify. Anything else is reverted.
ALLOWED_PATH_PREFIXES=(
    "libraries/stdlib/"
    "libraries/tools/kotlin-stdlib-gen/"
    "libraries/tools/binary-compatibility-validator/"
)

COMMIT_MSG_FILE=""
REVIEW_TMP=""
cleanup() { rm -f "${COMMIT_MSG_FILE:-}" "${REVIEW_TMP:-}"; }
trap 'cleanup; echo " Interrupted."; exit 130' INT TERM
trap 'cleanup' EXIT

FORCE_REGEN=false
if [[ "${1:-}" == "--force-regen" ]]; then
    FORCE_REGEN=true
    shift
fi

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 [--force-regen] <from> <to(included)>"
    echo "  --force-regen: re-generate review files even if they already exist"
    echo "  from: first review iteration number"
    echo "  to:   last review iteration number (included)"
    exit 1
fi

START=$1
END=$2

if ! [[ "$START" =~ ^[0-9]+$ ]] || ! [[ "$END" =~ ^[0-9]+$ ]]; then
    echo "ERROR: from/to must be positive integers" >&2
    exit 1
fi

if [[ $START -gt $END ]]; then
    echo "ERROR: from ($START) must be <= to ($END)" >&2
    exit 1
fi

# Resolve repo root: this script lives at
# libraries/stdlib/design/KT-10380-all-equal/review-loop.sh, i.e. 4 levels deep.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../../.."

DESIGN_DIR="libraries/stdlib/design/KT-10380-all-equal"
REVIEW_DIR="${DESIGN_DIR}/review"

# Discover top-level design docs (the review/ subdir is not matched by *.md).
shopt -s nullglob
DESIGN_DOCS=("${DESIGN_DIR}"/*.md)
shopt -u nullglob

if [[ ${#DESIGN_DOCS[@]} -eq 0 ]]; then
    echo "ERROR: No design docs found at ${DESIGN_DIR}/*.md" >&2
    exit 1
fi

# ---- Pre-flight checks ----
for cmd in codex claude git; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: '$cmd' not found in PATH" >&2
        exit 1
    fi
done

if ! git rev-parse --verify "${START_COMMIT}^{commit}" &>/dev/null; then
    echo "ERROR: START_COMMIT '${START_COMMIT}' is not a valid commit in this repo." >&2
    exit 1
fi

if [[ -n $(git status --porcelain) ]]; then
    echo "ERROR: Working tree has uncommitted changes. Commit or stash first." >&2
    git status --short >&2
    exit 1
fi

# A valid review must contain the "## Status" section
review_is_valid() { [[ -f "$1" ]] && grep -q '## Status' "$1"; }

# Status is "ready" only if the '## Status' section contains the magic phrase
review_status_is_ready() {
    [[ -f "$1" ]] && awk '
      /^## Status/ { in_s = 1; next }
      in_s && /^## / { exit }
      in_s && /Ready for merge/ { found = 1 }
      END { exit !found }
    ' "$1"
}

# Returns 0 (true) if the path is OUTSIDE every allowed prefix.
is_stray_path() {
    local path="$1"
    local prefix
    for prefix in "${ALLOWED_PATH_PREFIXES[@]}"; do
        if [[ "$path" == "$prefix"* ]]; then
            return 1
        fi
    done
    return 0
}

mkdir -p "$REVIEW_DIR"

# Live log files for `tail -f`; copied to per-iteration files after each run
CODEX_LIVE_LOG="${REVIEW_DIR}/log-codex.txt"
CLAUDE_LIVE_LOG="${REVIEW_DIR}/log-claude.txt"

echo "=== Review loop: KT-10380, iterations ${START}..${END} ==="
echo "=== Branch: $(git branch --show-current) ==="
echo "=== Start commit: ${START_COMMIT} ==="
echo "=== Design docs (${#DESIGN_DOCS[@]} files): ==="
printf '  %s\n' "${DESIGN_DOCS[@]}"
echo "=== Allowed path prefixes: ==="
printf '  %s\n' "${ALLOWED_PATH_PREFIXES[@]}"
echo ""

COMMITS_MADE=0
CONSECUTIVE_NO_CHANGES=0

print_iter_footer() {
    local nn=$1 start_epoch=$2
    local end_epoch elapsed
    end_epoch=$(date +%s)
    elapsed=$((end_epoch - start_epoch))
    echo "=== Iteration ${nn} finished at $(date '+%H:%M:%S') (elapsed: $((elapsed / 60))m $((elapsed % 60))s) ==="
    echo ""
}

for i in $(seq "$START" "$END"); do
    NN=$(printf '%02d' "$i")
    REVIEW_FILE="${REVIEW_DIR}/codex-review-${NN}.md"
    CODEX_LOG="${REVIEW_DIR}/log-${NN}-codex.txt"
    CLAUDE_LOG="${REVIEW_DIR}/log-${NN}-claude.txt"
    COMMIT_MSG_FILE="${REVIEW_DIR}/.commit-msg-${NN}.tmp"

    rm -f "$COMMIT_MSG_FILE"
    ITER_START_EPOCH=$(date +%s)

    echo "=== Iteration ${NN} started at $(date '+%H:%M:%S') ==="

    HEAD_BEFORE=$(git rev-parse HEAD)

    # ---- Phase 1: Codex writes review ----

    if [[ "$FORCE_REGEN" == true ]] && [[ -f "$REVIEW_FILE" ]]; then
        echo "[${NN}] --force-regen: removing existing review file."
        rm -f "$REVIEW_FILE"
    fi

    if review_is_valid "$REVIEW_FILE"; then
        echo "[${NN}] Review file already exists and is valid, skipping Codex phase."
    else
        if [[ -f "$REVIEW_FILE" ]]; then
            echo "[${NN}] WARNING: Review file exists but appears incomplete, regenerating."
            rm -f "$REVIEW_FILE"
        fi
        echo "[${NN}] Phase 1: Codex review..."

        # Collect previous reviews for context (current iteration's file is
        # already removed above if --force-regen, so it never appears here).
        shopt -s nullglob
        PREV_REVIEW_FILES=("${REVIEW_DIR}"/codex-review-*.md)
        shopt -u nullglob
        if [[ ${#PREV_REVIEW_FILES[@]} -gt 0 ]]; then
            PREV_REVIEWS=$(printf '   - %s\n' "${PREV_REVIEW_FILES[@]}")
        else
            PREV_REVIEWS="   (none — this is the first iteration)"
        fi

        # Codex writes to a temp file; we atomically move it on success
        REVIEW_TMP="${REVIEW_FILE}.tmp"
        rm -f "$REVIEW_TMP"

        CODEX_PROMPT="You are reviewing the Kotlin stdlib implementation of KT-10380 (the allEqual API family).

Read these inputs:

1. Authoritative design docs (the spec the implementation must satisfy):
$(printf '   - %s\n' "${DESIGN_DOCS[@]}")

2. The implementation under review. The implementation begins at commit
   ${START_COMMIT} and continues to HEAD. Inspect it via:
       git diff ${START_COMMIT}^..HEAD
   You can also read any file in the working tree directly.

3. Previous reviews — DO NOT repeat comments already addressed in the
   current state of the code:
${PREV_REVIEWS}

Write your review to: ${REVIEW_TMP}

Required structure:
   # Review of KT-10380 allEqual implementation (iteration ${NN})
   ## Summary
   ## Status
   ## Comments
   ## Conclusion

For each comment include:
- Priority: High / Medium / Low
- Title
- Locations (file:line references)
- Description: what diverges from the design docs or is internally inconsistent
- Concrete suggestion to fix
- Sources: design doc sections, official Kotlin docs, related YouTrack issues, source code

What to look for:
- Divergence between implementation and design docs in
  libraries/stdlib/design/KT-10380-all-equal/.
- Internal inconsistency between source-of-truth (kotlin-stdlib-gen
  templates / generators) and generated files (_Collections.kt, _Sequences.kt,
  _Arrays.kt, _UArrays.kt, AllEqual*Test.kt, AllEqual*Samples.kt).
- Floating-point semantics: NaN equals NaN and -0.0 != 0.0 for primitive
  array overloads, achieved via compareTo == 0 (not ==).
- Coverage gaps in tests / samples (every receiver family: Iterable,
  Sequence, Array<T>, primitive arrays, unsigned arrays).
- KDoc accuracy and consistency across overloads.
- @SinceKotlin(\"2.4\") + @ExperimentalStdlibApi presence on every public
  declaration.
- allEqualWith removal — no leftover references in stdlib sources, samples,
  tests, or public API dumps (design-doc mentions are fine).
- Public API dumps (libraries/tools/binary-compatibility-validator/) in
  sync with the generated declarations.

What NOT to flag:
- Commit message style, commit organization, or how the work is split
  across commits. The branch will be squashed into a single commit before
  it lands on master.
- Comments already addressed in the current code state, even if they
  appear in earlier reviews.

Verification methods (pick what fits the artifact):
- Generated-vs-template consistency: read both, or regenerate locally
  with ./gradlew :tools:kotlin-stdlib-gen:run /
  ./gradlew :tools:kotlin-stdlib-gen:generateStdlibTests and compare.
- Test coverage: enumerate cases against an explicit reference list.

Status — pick exactly one:
- \"Needs revision\" — at least one High comment OR implementation
  diverges from the design docs.
- \"Ready with minor remarks\" — only Medium/Low comments; merge is not
  blocked. List the remaining remarks explicitly.
- \"Ready for merge\" — no further substantive comments.

Language: English."

        PHASE1_START=$(date +%s)
        echo "=== Run started at $(date '+%Y-%m-%d %H:%M:%S') (iteration ${NN}) ===" > "$CODEX_LIVE_LOG"
        # Note: --full-auto grants Codex unrestricted shell access
        if ! codex exec --full-auto --ephemeral "$CODEX_PROMPT" \
                >> "$CODEX_LIVE_LOG" 2>&1; then
            cp -- "$CODEX_LIVE_LOG" "$CODEX_LOG"
            echo "[${NN}] ERROR: Codex crashed (see ${CODEX_LOG}). Aborting." >&2
            exit 1
        fi
        cp -- "$CODEX_LIVE_LOG" "$CODEX_LOG"
        PHASE1_ELAPSED=$(( $(date +%s) - PHASE1_START ))
        echo "[${NN}] Phase 1 done ($((PHASE1_ELAPSED / 60))m $((PHASE1_ELAPSED % 60))s)"

        # Atomic move: only promote temp file if it looks valid
        if review_is_valid "$REVIEW_TMP"; then
            mv -f "$REVIEW_TMP" "$REVIEW_FILE"
        else
            echo "[${NN}] WARNING: Review temp file missing or incomplete (no '## Status' section)."
            rm -f "$REVIEW_TMP"
        fi
    fi

    # ---- Phase 2: Verify review file ----
    if [[ ! -f "$REVIEW_FILE" ]]; then
        echo "[${NN}] Phase 2: ERROR — review file not created. Skipping Claude phase."
        print_iter_footer "$NN" "$ITER_START_EPOCH"
        continue
    fi
    echo "[${NN}] Phase 2: Review verified, $(wc -l < "$REVIEW_FILE") lines"

    if review_status_is_ready "$REVIEW_FILE"; then
        echo "[${NN}] Phase 2: Review status READY. Stopping loop."
        print_iter_footer "$NN" "$ITER_START_EPOCH"
        break
    fi

    # ---- Phase 3: Claude validates and fixes ----
    echo "[${NN}] Phase 3: Claude validates and fixes..."

    CLAUDE_PROMPT="You are a code editor for the KT-10380 (allEqual API) implementation in the
Kotlin standard library.

Inputs:
- The review to act on: ${REVIEW_FILE}
- Design docs (the spec):
$(printf '   - %s\n' "${DESIGN_DOCS[@]}")
- The implementation: git diff ${START_COMMIT}^..HEAD (read any file in
  the tree directly).

For each comment in the review:
1. Verify the issue still exists in the current code state — earlier
   iterations may have already fixed it. Verify with the method that
   matches the artifact:
   - Source code: read the file or run rg / grep.
   - Generated artifacts vs. templates: read both, or regenerate locally
     and diff.
   - Design alignment: cross-reference the relevant design doc.
2. If the issue is real, apply the fix.
   - Source-of-truth lives in kotlin-stdlib-gen. After editing templates
     or generators, regenerate downstream:
         ./gradlew :tools:kotlin-stdlib-gen:run
         ./gradlew :tools:kotlin-stdlib-gen:generateStdlibTests
     IMPORTANT: revert any incidental MinMax* changes immediately after
     generateStdlibTests via:
         git checkout HEAD -- libraries/stdlib/test/generated/minmax/
     (see libraries/stdlib/design/KT-10380-all-equal/Removal-steps.md,
     \"Revert any incidental MinMax* changes\" note.)
   - For changes affecting the public API surface, regenerate the dumps:
         ./gradlew :tools:binary-compatibility-validator:test \\
             -Doverwrite.output=true -Pkotlin.native.enabled=true
     This refreshes the JVM dump under
     libraries/tools/binary-compatibility-validator/reference-public-api/
     and the merged JS / Wasm / Native KLIB dump under
     libraries/tools/binary-compatibility-validator/klib-public-api/.
   - Preserve the style and structure of files you edit.
3. If the issue is invalid (already fixed, or reviewer was mistaken),
   skip it.

Constraints:
- You may modify ONLY files under these prefixes:
$(printf '    %s\n' "${ALLOWED_PATH_PREFIXES[@]}")
  Anything outside is reverted by the script.
- The script owns staging and committing. Do NOT run git add, git commit,
  git push, git reset, git rebase, git merge, or anything that touches the
  index, HEAD, or remotes.
- You MAY use read-only git commands (git diff, git status, git log,
  git show) freely, and working-tree-only reverts to restore committed
  files (git checkout HEAD -- <specific path>, git restore -- <specific path>).
  The MinMax* revert above is the canonical use case.

After all fixes:
- If you applied at least one fix, write a single-line description of
  the main fixes (English, under 200 chars) to:
      ${COMMIT_MSG_FILE}
  Format: apply review ${NN} — <description>
  Do NOT add any prefix — the script will prepend \"KT-10380 review: \".
- If no fix was needed, do NOT create the commit message file.

Ultrathink."

    PHASE3_START=$(date +%s)
    echo "=== Run started at $(date '+%Y-%m-%d %H:%M:%S') (iteration ${NN}) ===" > "$CLAUDE_LIVE_LOG"
    # Note: --dangerously-skip-permissions grants Claude unrestricted shell access
    if ! claude -p \
            --dangerously-skip-permissions \
            --no-session-persistence \
            --output-format stream-json \
            --verbose \
            "$CLAUDE_PROMPT" \
            >> "$CLAUDE_LIVE_LOG" 2>&1; then
        cp -- "$CLAUDE_LIVE_LOG" "$CLAUDE_LOG"
        echo "[${NN}] ERROR: Claude crashed (see ${CLAUDE_LOG}). Aborting." >&2
        exit 1
    fi
    cp -- "$CLAUDE_LIVE_LOG" "$CLAUDE_LOG"
    PHASE3_ELAPSED=$(( $(date +%s) - PHASE3_START ))
    echo "[${NN}] Phase 3 done ($((PHASE3_ELAPSED / 60))m $((PHASE3_ELAPSED % 60))s)"

    # ---- Phase 4: Commit if changes were made ----

    # Safety net: check if Claude committed despite instructions
    HEAD_AFTER=$(git rev-parse HEAD)
    if [[ "$HEAD_BEFORE" != "$HEAD_AFTER" ]]; then
        echo "[${NN}] WARNING: Claude made a commit despite instructions. Accepting it."
        echo "[${NN}] Claude committed: $(git log --oneline -1)"
        COMMITS_MADE=$((COMMITS_MADE + 1))
        CONSECUTIVE_NO_CHANGES=0
        rm -f "$COMMIT_MSG_FILE"
        print_iter_footer "$NN" "$ITER_START_EPOCH"
        continue
    fi

    # Revert tracked changes outside allowed prefixes
    STRAY_FILES=()
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        if is_stray_path "$f"; then
            STRAY_FILES+=("$f")
        fi
    done < <(git diff HEAD --name-only)
    if [[ ${#STRAY_FILES[@]} -gt 0 ]]; then
        echo "[${NN}] WARNING: Claude modified files outside allowed prefixes, reverting:"
        printf '  %s\n' "${STRAY_FILES[@]}"
        echo "=== Stray file diff (iteration ${NN}, reverted) ===" >> "$CLAUDE_LOG"
        git diff HEAD -- "${STRAY_FILES[@]}" >> "$CLAUDE_LOG"
        git checkout HEAD -- "${STRAY_FILES[@]}"
    fi

    # Remove untracked files Claude may have created outside allowed prefixes
    UNTRACKED_STRAY=()
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        if is_stray_path "$f"; then
            UNTRACKED_STRAY+=("$f")
        fi
    done < <(git ls-files --others --exclude-standard)
    if [[ ${#UNTRACKED_STRAY[@]} -gt 0 ]]; then
        echo "[${NN}] WARNING: Claude created untracked files outside allowed prefixes, removing:"
        printf '  %s\n' "${UNTRACKED_STRAY[@]}"
        rm -f -- "${UNTRACKED_STRAY[@]}"
    fi

    # Stage everything under the allowed prefixes; then check if anything was staged.
    git add -- "${ALLOWED_PATH_PREFIXES[@]}"

    if ! git diff --cached --quiet; then
        if [[ -f "$COMMIT_MSG_FILE" ]] && [[ -s "$COMMIT_MSG_FILE" ]]; then
            COMMIT_DESC=$(head -n 1 -- "$COMMIT_MSG_FILE" | cut -c1-200)
            COMMIT_MSG="KT-10380 review: ${COMMIT_DESC}"
        else
            COMMIT_MSG="KT-10380 review: apply review ${NN} fixes"
        fi

        echo "$COMMIT_MSG" | git commit -F -
        echo "[${NN}] Committed: $(git log --oneline -1)"
        COMMITS_MADE=$((COMMITS_MADE + 1))
        CONSECUTIVE_NO_CHANGES=0
    else
        echo "[${NN}] No changes from this iteration."
        CONSECUTIVE_NO_CHANGES=$((CONSECUTIVE_NO_CHANGES + 1))
        if [[ $CONSECUTIVE_NO_CHANGES -ge 3 ]]; then
            echo "=== ${CONSECUTIVE_NO_CHANGES} consecutive no-change iterations. Stopping. ==="
            rm -f "$COMMIT_MSG_FILE"
            print_iter_footer "$NN" "$ITER_START_EPOCH"
            break
        fi
    fi

    rm -f "$COMMIT_MSG_FILE"
    print_iter_footer "$NN" "$ITER_START_EPOCH"
done

echo "=== Done. Commits: ${COMMITS_MADE}, Consecutive no-change: ${CONSECUTIVE_NO_CHANGES} ==="
