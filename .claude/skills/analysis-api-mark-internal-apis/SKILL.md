---
name: analysis-api-mark-internal-apis
description: Find and mark non-internal declarations in Analysis API implementation modules with appropriate visibility annotations
user-invocable: true
disable-model-invocation: true
argument-hint: "[--intellij=<path>] [--dry-run] [--filter=<glob>]"
---

# Mark Internal APIs in Analysis API Implementation Modules

Declarations in Analysis API implementation modules should not be exposed to users. They should be `internal` or annotated
with a visibility annotation. This skill finds non-internal, non-annotated declarations and marks them based on their usage
patterns.

**Reference:** Read [Guard API Endpoints with Annotations](/analysis/docs/contribution-guide/api-development.md#guard-api-endpoints-with-annotations)
for the full annotation guide and placement rules.

## Inputs

### Module selection (required)

Use `AskUserQuestion` to present a selection of modules to scan. The user must pick one:

| Choice                    | Module path (project-relative)     |
|---------------------------|------------------------------------|
| `analysis-api-fe10`       | `analysis/analysis-api-fe10`       |
| `analysis-api-fir`        | `analysis/analysis-api-fir`        |
| `analysis-api-impl-base`  | `analysis/analysis-api-impl-base`  |
| `analysis-internal-utils` | `analysis/analysis-internal-utils` |
| `low-level-api-fir`       | `analysis/low-level-api-fir`       |

### Optional flags

Parse the following from the argument string. All are optional.

| Input         | Flag                | Default                                  | Description                                                          |
|---------------|---------------------|------------------------------------------|----------------------------------------------------------------------|
| IntelliJ repo | `--intellij=<path>` | `../ultimate` (relative to project root) | Path to the IntelliJ repository for additional usage search          |
| Dry run       | `--dry-run`         | `false`                                  | Only report findings, do not modify files                            |
| File filter   | `--filter=<glob>`   | *(none)*                                 | Restrict scan to matching files within the module's `src/` directory |

---

## Annotations Reference

### Exclusion annotations

Skip declarations already annotated with **any** of these (they are already visibility-restricted):

- `@KaExperimentalApi`
- `@KaPlatformInterface`
- `@KaNonPublicApi`
- `@KaIdeApi`
- `@KaImplementationDetail`
- `@LLFirInternals`

Also skip:
- `internal` and `private` declarations
- `annotation class` declarations annotated with `@RequiresOptIn` — these are opt-in markers themselves and must remain
  public so other modules can reference them in `@OptIn(...)`

### Target annotations

The annotation for "test-only" usages depends on the selected module:

**For `low-level-api-fir`:**

| Usage classification | Action |
|----------------------|--------|
| No usages outside `<module>/src/` | Add `internal` visibility modifier |
| Usages only in test sources | Annotate with `@LLFirInternals` |
| Usages in production code outside the module | Annotate with `@KaImplementationDetail` |

**Exception:** Declarations in the `org.jetbrains.kotlin.analysis.low.level.api.fir.api` package (the LL FIR public
API surface) follow the same two-way rules as other modules — they should **never** be annotated with `@LLFirInternals`.
Use `internal` if unused outside the module, or `@KaImplementationDetail` for any external usages (test or production).

**For all other modules** (`analysis-api-fe10`, `analysis-api-fir`, `analysis-api-impl-base`, `analysis-internal-utils`):

| Usage classification | Action |
|----------------------|--------|
| No usages outside `<module>/src/` | Add `internal` visibility modifier |
| Any usages outside the module (test or production) | Annotate with `@KaImplementationDetail` |

---

## Phase 1: Scan for candidates

### Step 1: Find source files

Find all `.kt` files under `<module>/src/`. If `--filter` is provided, restrict to matching files.
Exclude any generated files or build output.

### Step 2: Identify declarations that need marking

For each source file, identify **non-internal, non-private** declarations that are not annotated with any exclusion annotation:

**In scope:**
- **Top-level declarations:** classes, interfaces, objects, functions, properties, type aliases, enum classes, sealed
  classes/interfaces, annotation classes
- **Nested classifiers:** classes, interfaces, objects (including companion objects), enum classes within other classifiers

**Out of scope (skip):**
- Declarations with `internal` or `private` visibility
- Declarations annotated with any exclusion annotation listed above
- Nested non-classifier members (functions, properties) — these inherit effective visibility from their containing classifier
- Declarations inside an `internal` or `private` parent classifier (already effectively restricted)

**Practical approach for scanning:**
1. Search for files containing top-level declarations without `internal`/`private` modifiers. Top-level declarations
   in Kotlin start at column 0 (no indentation), possibly preceded by annotations on the lines above.
2. Read candidate files to confirm which declarations need marking. Check both the declaration line and any
   annotations on preceding lines.
3. For nested classifiers, check whether the containing classifier is already `internal`/`private` or annotated.

### Step 3: Present candidates and confirm

Present all found candidates grouped by file:
- File path
- For each declaration: name, kind (`class`, `fun`, `val`, `object`, etc.), current visibility (`public`/default)

Ask the user to confirm before proceeding. They may want to exclude certain declarations or adjust the scope.

If `--dry-run` is specified, the user confirmation should note that Phase 2 will only report classifications without modifying files.

---

## Phase 2: Classify and annotate

Process each candidate declaration. Work through files sequentially — annotate all candidates in a file before moving
to the next one, so that imports and annotations can be batched.

### Step 1: Search for usages outside the module

Use **JetBrains MCP** for searching within the Kotlin project. The MCP does not have a dedicated "Find Usages" tool,
so use `search_text` (preferred for exact names) or `search_regex` with path filtering instead.

- **Within the Kotlin project (JetBrains MCP):** Use `search_text` or `search_regex` with `paths` to exclude the
  module's own source directory (e.g., `["!analysis/low-level-api-fir/src/**"]`).
- **Within the IntelliJ repository:** The IntelliJ repo is a separate project, so JetBrains MCP cannot search it.
  Use standard `Grep` to search the IntelliJ repo at the `--intellij` path. 
  - If the project cannot be found, notify the user but don't abort. Continue searching for usages in the Kotlin 
    project.

**Search scope and classification:**
- Usages in `<module>/src/` → **ignore** (same module)
- Usages in any `tests/`, `test/`, or `testData/` directory (in any module) → **test usage**
- Usages in any other source directory → **production usage**
- Usages in the IntelliJ repository → **production usage**

**Reducing false positives:**
- For declarations with common names (e.g., `create`, `get`, `resolve`), search for the qualified name or
  a distinctive usage pattern (e.g., `ClassName.methodName`, `import ...ClassName`).
- When results are ambiguous, read the usage site to confirm it references this specific declaration.
- For classes/interfaces/objects, searching for the name is usually sufficient since classifier names tend to be unique.

### Step 2: Classify the declaration

Apply the target annotation rules from the [Annotations Reference](#target-annotations) section based on the selected module.

### Step 3: Apply the annotation

**If `--dry-run`:** Report the classification without modifying files. Continue to the next candidate.

**Otherwise:**

- **`internal`**: Add the `internal` visibility modifier before the declaration keyword.
  - If the declaration currently says `public`, replace `public` with `internal`.
  - If the declaration has no explicit visibility modifier, add `internal` before the declaration keyword.

- **`@LLFirInternals`** (only for `low-level-api-fir`): Add the annotation on the line above the declaration
  (after any existing annotations, before the declaration keyword line). Add the import if not present:
  ```
  import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
  ```

- **`@KaImplementationDetail`**: Add the annotation on the line above the declaration. Add the import if not present:
  ```
  import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
  ```

### Step 4: Propagate annotations to nested classifiers

After annotating a top-level classifier with `@LLFirInternals` or `@KaImplementationDetail`, **re-read the file** and
check the classifier body for **non-internal, non-private nested classifiers** — including **companion objects**, nested
classes, interfaces, objects, and enum classes. Add the **same annotation** to each of them. This is required because the
binary compatibility checker does not propagate opt-in annotations from outer to nested classes (see the
[placement rules](/analysis/docs/contribution-guide/api-development.md#guard-api-endpoints-with-annotations)).

This step does not apply to classifiers marked `internal` — the compiler enforces `internal` visibility transitively.

### Step 5: Flag override declarations

**Override declarations:** If a candidate declaration has the `override` keyword, or if making it more restrictive
would violate a contract (e.g., it implements an interface member defined in a more visible scope), **flag it for
manual review** instead of annotating it. Report these separately in the summary.

---

## Phase 3: Verify

### Step 1: Check for problems

Run JetBrains MCP `get_file_problems` with `errorsOnly=false` on each modified file. Fix any warnings or errors
related to the changes (e.g., missing imports, opt-in requirements in the same module).

---

## Phase 4: Build and fix exposure errors

### Step 1: Build the module

Build the scanned module using the appropriate Gradle command, e.g.:
```bash
./gradlew :analysis:low-level-api-fir:compileKotlin -q
```
Adapt the Gradle path to match the selected module.

### Step 2: Fix "internal declaration exposed through public API" errors

When a declaration was marked `internal` but is used in the signature of a non-internal declaration (parameter type,
return type, supertype, etc.), the compiler reports an "internal declaration exposed through public API" error at the
use-site.

**Fix:** Replace `internal` with `@KaImplementationDetail` on the **exposed declaration** (the one that was marked
`internal`), not the use-site. The use-site declaration likely needs to remain public at this point.

This is a blind spot during the research phase — in-module usages may expose a declaration through a public API, but
at research time it is unclear whether the use-site declaration will also become `internal`. The compiler resolves this
ambiguity after all changes are applied.

### Step 3: Rebuild until clean

After fixing exposure errors, rebuild to check for cascading issues (fixing one exposure might reveal another).
Repeat until the build succeeds.

### Step 4: Final summary

Present the final summary:
- Count and list of declarations marked `internal`
- Count and list of declarations marked `@LLFirInternals` (if `low-level-api-fir`)
- Count and list of declarations marked `@KaImplementationDetail`
- Declarations that were changed from `internal` to `@KaImplementationDetail` due to exposure errors
- Count and list of declarations flagged for manual review
- Any remaining issues
