# AGENTS.md

Guidance for agents working in this repository. Everything here was verified against the code and the compiler; keep it
current when behavior changes.

Keep this file to persistent design and rules: facts that go stale quickly — version numbers, annotation or file lists,
test counts — belong in the code, and this file points to the source instead of copying it.

## What this is

- Kotlin Multiplatform library `dsl-utilities` (the annotation-driven DSL API and its validator/mapper contracts) plus
  the JVM KSP processor `dsl-utilities-ksp`, which generates builders, result data classes and `build…` functions.
- Code generation is built on KotlinPoet and KSP; current tool versions live in `gradle/libs.versions.toml`.
- The processor lives under `dsl-utilities-ksp/src/main/kotlin` as focused files rather than one file; keep it that way.
  Pipeline: `DslProcessor.process` → `Generation.generate` phases (spec members → child scopes → result-supertype
  validation → name reservation → `FileContext` allocation → emission). `Checker` holds per-spec buffered diagnostics,
  `FileContext` holds per-file name allocation and import aliasing, `DslProcessor` holds cross-round registries.
- Tests and fixtures live in `dsl-utilities/src/jvmTest/kotlin`; KSP runs as `kspJvmTest`, the generated sources under
  its `build/generated/ksp` tree are asserted as text by some tests, and those tests resolve the path relative to the
  repository root.

## Commands

- Fast loop: `./gradlew :dsl-utilities-ksp:compileKotlin :dsl-utilities:jvmTest --rerun`
- Trustworthy verification (Gradle caching and the configuration cache can hide failures):
  `./gradlew :dsl-utilities-ksp:compileKotlin :dsl-utilities-ksp:jar :dsl-utilities:jvmTest --no-build-cache --rerun-tasks`
- One test: `./gradlew :dsl-utilities:jvmTest --tests "*AliasHandlingTest"`
- CI (macOS, current JDK): `kotlinNodeJsSetup kotlinWasmNodeJsSetup kotlinNpmInstall kotlinWasmNpmInstall` followed by
  `./gradlew allTests --continue`; releases publish with `publishToMavenCentral`.
- The processor module pins its Java and Kotlin targets to the same value; without that pin, a cold build on a newer JDK
  fails with an `Inconsistent JVM-target compatibility` error between `compileJava` and `compileKotlin`.

## Hard rules

- All experiments, clones and temporary files go under `%TEMP%\opencode\<subdir>`. Never create files in
  `Documents\Projects` (any IDE project directory) or in a bare temp root, and never modify the repository from a probe.
- Do not commit anything on your own unless the session agreement covers it. Decide on autonomous commits — a
  long-running task that needs history, or a non-main branch — **before the task starts** and request authorization with
  the question tool.
- Do not push, merge, or rewrite published history. The owner pushes branches; unpushed commits may be rewritten when a
  commit is misplaced or mis-scoped (split them instead of adding a fixup commit). Conventional commits with a scope:
  `fix(ksp):`, `test(ksp):`, `refactor(ksp):`, `docs(ksp):`, `build(ksp):`, `style(ksp):`; one concern per commit, and
  fixes and their regression tests stay separate.
- No fully qualified names in source code; import instead.
- Comments and KDoc are English: describe the current behavior positively, never the change history ("previously",
  "moved", "old"), and never negate something the surrounding text does not establish. State a concrete reason for
  surprising behavior.
- Fix every instance of a problem class, not only the reported one; scope may exceed the report, but avoid speculative
  abstractions — document a boundary instead of adding a check for an unreachable case.

## Engineering rules

- Investigate before implementing. When code mirrors compiler behavior — name parsing, signatures and overloads,
  visibility, synthesized members (`componentN`/`copy`/`equals`/`hashCode`/`toString`), call resolution — verify it
  against the real compiler with a minimal probe first, then encode what the probe shows.
- Reuse the mature tooling. KotlinPoet and KSP already model types and code; do not hand-roll what they provide. Prefer
  `ClassName`/`TypeName`/`MemberName` and KSP declarations over string parsing; a string-typed key or a `"Foo.Bar.Baz"`
  literal is a smell.
- Keep one source of truth: annotation and marker constants, stdlib `MemberName`s, the literal table, file name
  allocation and signature comparison each have one home. Extend the existing table or branch instead of adding a
  parallel one.
- Generated output is the contract: after a refactor, compare the generated files byte-for-byte and keep the diff
  limited to the fixtures the change intends.
- No compile-testing infrastructure (no kctfork/`symbol-processing-testing`): negative diagnostics are verified with
  temporary probe fixtures. Do not add the dependency.

## KotlinPoet / KSP boundaries (verified)

- A data class is a `TypeSpec.classBuilder(…).addModifiers(KModifier.DATA)` with a primary constructor plus same-named
  properties; KotlinPoet has no data-class API and does not model synthesized members, so collision checks with them
  belong to the processor.
- `ParameterizedTypeName.parameterizedBy` drops the enclosing type; inner classes of generic outers must rebuild the
  qualifier chain (`nestedClass`).
- `%S` trims trailing newlines and drops unpaired surrogates through the generated file's encoding; use `literalString`
  for strings and `escapeCharLiteral` for chars.
- Copied type-use annotations go through kotlinpoet-ksp's `toAnnotationSpec()`, which renders unpaired surrogates as
  `?`.
- Anything a DSL member could shadow must be referenced through `FileContext.member`/`expression` so it receives an
  import alias; raw identifiers in generated expressions can be captured.
- KSP: `containingFile == null` marks declarations compiled from a dependency (`internal` there is inaccessible);
  `getConstructors()` covers secondary constructors; an inner class's arguments arrive own-first then outer;
  `KSValueArgument.isDefault()` means the argument fell back to its default.

## Change workflow

- Before committing: run the IDE formatter on every file changed since the last formatting pass (derive the list from
  git instead of guessing), then the IDE lint at warning severity until it reports nothing, then the build and tests.
  The formatter can overwrite a file from a stale editor buffer — re-check `git diff` after it runs.
- Probe fixtures: temporary files under `dsl-utilities/src/jvmTest/kotlin` with unique names; run
  `:dsl-utilities:kspTestKotlinJvm` for KSP diagnostics or `:dsl-utilities:compileTestKotlinJvm` for generated-code
  compilation, then delete them. A KSP error aborts compilation, so run diagnostics-expected and compile-expected probes
  in separate batches.
- Review is part of the loop, not the goal: an independent branch-level review runs before merge; its findings are
  classified as introduced-by-this-branch versus pre-existing and fixed in their own commits with probe or
  regression-test evidence. Keep that evidence in the report, not in comments.

## Deliberate decisions (do not "fix")

- Unsupported declarations are rejected with diagnostics (member extensions, non-`@DslChild` functions, conflicting
  names, incompatible supertype members) instead of generating uncompilable code; extend the diagnostics rather than
  loosening the checks.
