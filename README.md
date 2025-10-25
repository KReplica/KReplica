# KReplica

KReplica is a DTO generator for KMP and Kotlin JVM. It runs automatically during Kotlin compilation.

Also check out the new [website](https://kreplica.availe.io) for KReplica. The documentation site is currently more up-to-date than the README; except the fact that it currently does not cover the nested contextual feature.

### Features:

* **Variant generation:** From a single interface, specify up to three variants to generate:
    * `DATA`: For read-only data representation
    * `CREATE`: For object creation requests
    * `PATCH`: For update requests, wraps properties in a `Patchable` type.
* **Granular control:** Enable or disable specific features or variants at both the model and property levels.
* **Nominal Typing:** Automatically wrap primitive types in value classes for added type safety and clarity.
* **Schema Versioning:** Defines a sealed interface for each schema version, enabling exhaustive when expressions.
* **Plain Kotlin:** Emits plain Kotlin source files to your build directory, free of framework-specific code or runtime
  dependencies.

## Quick Start

Add the KSP and KReplica plugins to your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" // Use a KSP version that matches your Kotlin version
    id("io.availe.kreplica") version "5.0.5"
}
```

## A quick note on sealed hierarchies

KReplica generates sealed interfaces, allowing you to leverage exhaustive `when` expressions for robust and type-safe code. This makes handling different versions or variants straightforward and ensures you don't miss cases.

In **example 2 (versioned schema)**, if you check the generated code, here is a short snippet of what you will see:

```kotlin
    public sealed interface V2 : UserAccountSchema {
        public data class Data(
            public val id: Int,
            public val name: String,
            public val schemaVersion: Int = 2,
        ) : V2,
            DataVariant
```

This code snippet might seem a bit complex for a single DTO, but it's key to the examples below:

**Example: Exhaustively handle all schema versions and variants**

```kotlin
fun handleUser(user: UserAccountSchema) {
    when (user) {
        is UserAccountSchema.V1.Data -> TODO()
        is UserAccountSchema.V2.Data -> TODO()
        is UserAccountSchema.V2.PatchRequest -> TODO()
    }
}
```

**Example: Exhaustively handle only a specific schema version**

```kotlin
fun handleUser(user: UserAccountSchema.V2) {
    when (user) {
        is UserAccountSchema.V2.Data -> TODO()
        is UserAccountSchema.V2.PatchRequest -> TODO()
    }
}
```

**Example: Exhaustively handle only a specific kind of variant (e.g., all data variants)**

```kotlin
fun handleUser(user: UserAccountSchema.DataVariant) {
    when (user) {
        is UserAccountSchema.V1.Data -> TODO()
        is UserAccountSchema.V2.Data -> TODO()
    }
}
```

**Example: Target only a specific schema version and specific variant**

```kotlin
fun handleUser(user: UserAccountSchema.V1.Data) {
    print(user.id)
}
```

## Other addenda

**Note 1:** KReplica generates all output files in your module’s `build/generated-src/kotlin-poet/` directory.

**Note 2:** 90% of what you need to know to utilize KReplica is covered by the first two examples:
- `Replicate.Model` and `Replicate.Property` (example 1)
- Versioned schemas (example 2)

## Example (non-versioned)

This example covers how to use the `Replicate.Model` and `Replicate.Property` annotations.

```kotlin
@OptIn(ExperimentalUuidApi::class)
@Replicate.Model(
  variants = [DtoVariant.DATA, DtoVariant.CREATE, DtoVariant.PATCH], // required argument
  nominalTyping = NominalTyping.ENABLED // disabled by default
)
private interface UserAccount {
  // This property inherits all of @Replicate.Model's arguments
  val emailAddress: String

  // This property is only included in the DATA variant
  @Replicate.Property(include = [DtoVariant.DATA])
  val id: Uuid

  // This property is excluded from the CREATE variant
  @Replicate.Property(exclude = [DtoVariant.CREATE])
  val banReason: Patchable<List<String?>>

  // We opt out of nominalTyping for this property
  @Replicate.Property(nominalTyping = NominalTyping.DISABLED)
  val userDescription: String?
}
```

See the [generated code](docs/EXAMPLES.md#example-non-versioned).

See the [patchable file](docs/EXAMPLES.md#patchable)


## Example (versioned)

To version a `Replicate.Model` declaration, create a base interface (e.g. `UserAccount`) and extend it with V\<number\>
interfaces (e.g. `V1`, `V2`) to track model changes.

```kotlin
private interface UserAccount

@Replicate.Model(variants = [DtoVariant.DATA])
private interface V1 : UserAccount {
    val id: Int
}

@Replicate.Model(variants = [DtoVariant.DATA, DtoVariant.PATCH])
private interface V2 : UserAccount {
    val id: Int
    val name: String
}
```

See the [generated code](docs/EXAMPLES.md#example-versioned)

If you wish to not follow the V\<number\> naming convention, you must use the `Replicate.SchemaVersion` annotation to
manually
specify a version number.

```kotlin
private interface UserAccount

@Replicate.Model(variants = [DtoVariant.DATA, DtoVariant.PATCH])
@Replicate.SchemaVersion(1)
private interface NewAccount : UserAccount {
    val id: Int
    val name: String
}
```

See the [generated code](docs/EXAMPLES.md#example-versioned-w-schema-version-annotation)

Note all versioned declarations automatically inject a `schema_version` property into the generated DTOs.

## Directly applying annotations

If the annotation can be applied on interfaces, you can directly use it without the need for `Replicate.Apply`. For
example:

```kotlin
@Replicate.Model(variants = [DtoVariant.DATA])
@Deprecated("Use NewUserAccount instead")
private interface UserAccount {
    @Deprecated("Use newId instead")
    val id: Int
}
```

See the [generated code](docs/EXAMPLES.md#directly-applying-annotations)

## Example (serializable)

Interfaces cannot directly implement some annotations, including `Serializable`. Instead, you can use Note
`Replicate.Apply`.

```kotlin
@OptIn(ExperimentalTime::class)
object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}
```

```kotlin
private interface UserAccount // this is a versioned schema declaration

@OptIn(ExperimentalTime::class)
@Replicate.Model(variants = [DtoVariant.DATA], autoContextual = AutoContextual.DISABLED)
@Replicate.Apply([Serializable::class])
private interface V1 : UserAccount {
  val id: Int

  // here we manually apply contextual, as it inherits the model's AutoContextual.DISABLED
  @Contextual
  val startTime: Instant

  // here we manually specify which serializer we want to use
  @Serializable(with = InstantAsStringSerializer::class)
  val midTime: Instant

  // here we override autoContextual to be ENABLED and let KReplica apply it automatically
  @Replicate.Property(autoContextual = AutoContextual.ENABLED)
  val endTime: List<List<Instant>>
}
```

See the [generated code](docs/EXAMPLES.md#example-serializable)

Note that `Replicate.Apply` can also take include/exclude arguments, if you want an annotation to only be
applied to a
specific variant. Additionally, `Replicate.Apply` is repeatable and takes a list of annotations.

The drawback of `Replicate.Apply` is that the IDE no longer warns when `Contextual` is needed. To address
this,
KReplica recursively applies
`Contextual` in generated code, so it works regardless of generic nesting.

If you wish, you can turn off the autoContextual flag on `Replicate.Model` or `Replicate.Property`.

Types exempt from `Contextual` are whitelisted in `codegen-models/src/commonMain/kotlin/io/availe/models/Constants.kt`.

## The hide annotation

The `@Replicate.Hide` annotation stops a `Replicate.Model` declaration from being generated. It's mainly for temporarily
testing how
code removal affects the system—but use it as you see fit.

```kotlin
@Replicate.Model(variants = [DtoVariant.DATA])
@Replicate.Hide
private interface UserAccount {
    val id: Int
}
```

## Contextual nested models

Note: Nested Contextuals only work for KReplica declaration that all reside within the same module. Adding multi-module support is possible, since each module creates a separate `models.json` file, which in theory could be merged for processing. However, it is not currently a priority for me, as I typically declare all my KReplica DTO's within a shared module that connects the client and server modules.

Say that you previously defined `UserAccount`:

```kotlin
private interface UserAccount

@Replicate.Model(variants = [DtoVariant.DATA, DtoVariant.CREATE, DtoVariant.PATCH], nominalTyping = NominalTyping.ENABLED)
private interface V1 : UserAccount {
    val id: Int
}
```

Now you want `UserAccount` to be included in `AdminAccount`, but in a particular format:

- `AdminAccount` DATA variant should include `UserAccount` data variant
- `AdminAccount` create variant should include `UserAccount` create variant
- `AdminAccount` patch variant should include `UserAccount` patch variant

You could use the `Replicate.Property` annotation to manually configure each field, or you can take advantage of
contextual nested models:

```kotlin
@Replicate.Model(variants = [DtoVariant.DATA, DtoVariant.CREATE, DtoVariant.PATCH])
private interface AdminAccount {
    val user: UserAccountSchema.V1
}
```

See the [generated code](docs/EXAMPLES.md#contextual-nested-model-versioned)

Or if `UserAccount` was a non-versioned schema:

```kotlin
@Replicate.Model(variants = [DtoVariant.DATA, DtoVariant.CREATE, DtoVariant.PATCH])
private interface AdminAccount {
    val user: UserAccountSchema
}
```

See the [generated code](docs/EXAMPLES.md#contextual-nested-model-non-versioned)

## FAQ

### Can a `Replication.Property` have a broader replication than its `Replication.Model`?

No. The replication of all children must be a subset of the parent (⊆), including for nested models. Otherwise, KReplica
will error and log the offending fields.
This rule ensures fail-fast feedback. If you restrict a parent’s replication but forget to update a child field, you’ll
get an immediate build-time error.

### If a `Replicate.Model` has another `Replicate.Model` as a field, does the order of compilation matter?

No. KReplica actually cleans the build folder at the start of each run (which ensures no stale data). To ensure that
nested contextuals work, KReplica uses two-pass compilation. Prior to the main compilation, stub files of all
`Replicate.Model` declarations, which is then deleted to avoid a redeclaration error.

### Why do all the examples use the private keyword (private interface)?

The `private` keyword is not required for KReplica to function. However, the KReplica interfaces are useless outside
of KReplica, so I prefer to private them so they don't contaminate the name space.

This is particularly important with versioned schemas, as the V[number] naming convention is repetitive and you cannot
redeclare interfaces. This does mean, however, that only one versioned schema can exist per file.
