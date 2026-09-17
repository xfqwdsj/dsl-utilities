# dsl-utilities

A Kotlin multiplatform library providing compile-time DSL builder generation
through [Kotlin Symbol Processing (KSP)](https://kotlinlang.org/docs/ksp-overview.html).

An interface annotated with `@DslBuilder` describes a DSL. The KSP processor
turns the declaration into a builder class, an immutable result data class,
and a top-level `build…` function. Validation logic and property names are
woven into the generated accessors as direct calls, and the built result is
locked against modification by the type system.

## Usage

Add the library and the KSP processor to a module:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

kotlin {
    jvm()
    // other targets…
}

dependencies {
    implementation("top.ltfan.dslutilities:dsl-utilities:<version>")
    add("kspJvm", "top.ltfan.dslutilities:dsl-utilities-ksp:<version>")
}
```

An Android target uses the `kspAndroid` configuration instead of `kspJvm`.

Declare a DSL specification:

```kotlin
object NonBlankValidator : DslValidator<String> {
    override fun validate(value: String) = value.isNotBlank()
}

object UpperCaseMapper : DslMapper<String, String> {
    override fun toStored(value: String) = value.lowercase()
    override fun toValue(stored: String) = stored.uppercase()
}

@DslBuilder
interface PersonDsl {
    // `val` properties are required values; the compiler enforces their
    // presence at every call site of the generated build function.
    val name: String

    @DslValue(validator = NonBlankValidator::class, message = "The title must not be blank.")
    val title: String

    // `var` properties are set inside the DSL block. Nullable properties
    // are optional and default to null.
    @DslValue
    var nickname: String?

    // `initial` carries a compile-time constant; `mapper` transforms values
    // between the declared type and the stored representation; `validator`
    // rejects values in the generated setter.
    @DslValue(initial = "default", mapper = UpperCaseMapper::class)
    var prepared: String

    // List properties are mutated inside the DSL block; their elements are
    // validated when the value is built.
    @DslList(validator = NonBlankValidator::class, message = "The tags must not be blank.")
    var tags: MutableList<String>
}
```

The processor generates `PersonDslBuilder`, the immutable result class
`Person`, and `buildPerson`:

```kotlin
val person = buildPerson(name = "LTFan", title = "Author") {
    nickname = "Fan"
    prepared = "Prepared"
    tags.add("kotlin")
}

person.name                 // "LTFan"
person.prepared             // "PREPARED"
person.tags                 // read-only list
// person.nickname = "x"    // compile error: the result exposes no setters
```

## Property semantics

| Declaration                       | Meaning                                                         |
|-----------------------------------|-----------------------------------------------------------------|
| `val name: String`                | Required value, a parameter of the generated build function     |
| `@DslValue var p: String`         | DSL value with a compile-time `initial` constant                |
| `@DslValue var p: String?`        | Optional DSL value defaulting to `null`                         |
| `@DslValue(validator = …::class)` | Value accepted only when the validator passes                   |
| `@DslValue(mapper = …::class)`    | Value transformed between the declared type and the stored type |
| `@DslList var p: MutableList<T>`  | DSL list, exposed as a read-only `List` in the result           |

Validators implement `DslValidator<T>`; mappers implement `DslMapper<I, O>`.

## Composition

Nested scopes and lists of children are declared in the spec, and the
processor generates the composition functions:

```kotlin
sealed interface Event

@DslBuilder(supertype = Event::class)
interface TransientEventDsl {
    @DslValue
    var intensity: Float?
}

@DslBuilder(supertype = Event::class)
interface ContinuousEventDsl {
    val at: Int

    @DslValue
    var label: String?
}

@DslBuilder
interface EventsDsl {
    // Generates `event`-style element functions on the block receiver:
    //   fun transientEvent(block: TransientEventDsl.() -> Unit = {})
    //   val transientEvent: Unit          // shorthand for the default child
    //   fun continuousEvent(at: Int, block: ContinuousEventDsl.() -> Unit = {})
    @DslList(children = [TransientEventDsl::class, ContinuousEventDsl::class])
    var events: MutableList<Event>
}

@DslBuilder
interface PatternDsl {
    // Generates the function body, which builds the child and stores it.
    @DslChild
    fun events(block: EventsDsl.() -> Unit = {})
}
```

```kotlin
val pattern = buildPattern {
    events {
        transientEvent { intensity = 0.8f }
        transientEvent
        continuousEvent(at = 20) { label = "low" }
    }
}
```

`@DslList(children = …)` derives one function per child from the child's
required properties: parameters carry those properties, the trailing block is
optional unless the child declares `@DslChild` functions of its own, and a
child without required properties and without `@DslChild` functions also
receives a property shorthand that adds a child built with the default
configuration. `@DslBuilder(supertype = …)` makes the generated result class
implement the supertype, which lets several children share the element type
of one list. `@DslChild` marks a function whose body the processor generates
from the child interface. The generated `build…` functions and element
functions are `inline`, so the configuration blocks compile into the call
site.

## Generated names

The generated names follow the annotated interface name, and each can be
overridden:

| Declaration                              | Generated                                             |
|------------------------------------------|-------------------------------------------------------|
| `@DslBuilder interface PersonDsl`        | `PersonDslBuilder`, `Person`, `buildPerson`           |
| `@DslBuilder interface Config`           | `ConfigBuilder`, `ConfigResult`, `buildConfig`        |
| `@DslBuilder(resultName = "Palette")`    | result class `Palette`                                |
| `@DslBuilder(builderName = "Assembler")` | builder class `Assembler`                             |
| `@DslBuilder(functionName = "person")`   | build function `person`                               |
| `@DslBuilder(generateFunction = false)`  | no build function; the builder class is used directly |

## Publishing generated code from a library

A library that ships DSLs can run the processor on `commonMain` so that the
generated declarations are part of the published metadata and are visible to
every target and to the library's consumers. Wire the metadata output into
`commonMain` and make the other KSP and compilation tasks run after the
metadata task:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
    }
}

tasks.configureEach {
    if (name.startsWith("ksp") && name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
tasks.withType<Jar>().configureEach {
    if (name.contains("sourcesJar", ignoreCase = true)) {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

dependencies {
    add("kspCommonMainMetadata", "top.ltfan.dslutilities:dsl-utilities-ksp:<version>")
}
```

The generated classes then appear in the JVM/Android jars, the native klibs,
the common metadata jar, and the sources jar. A library that exposes its own
entry point instead sets `@DslBuilder(generateFunction = false)` and works
with the generated builder class.

## Migrating from 1.x

Version 2.0 replaces the hand-written `ValueDsl`, `Value`, and
`DslMutableList` API, along with the value and list property types that
supported them, with annotation-driven generation, so code written against
the 1.x DSL helpers does not compile unchanged. Rewrite a 1.x
specification as a plain interface whose members carry `@DslValue`,
`@DslList`, and `@DslChild` as shown in [Usage](#usage), and add the KSP
processor to the consuming module; the generated builders, results, and
`build…` functions follow the shapes described in this document.

## Modules

- `dsl-utilities` — the annotations, `DslValidator`, and `DslMapper`, with
  support for all Kotlin multiplatform targets.
- `dsl-utilities-ksp` — the KSP processor generating the builders.

## License

[MIT](https://opensource.org/license/mit/)
