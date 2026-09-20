package fix.findings

import fix.Finding

/** Finding kinds of the idiom rules: `PreferEffectIdioms`, `PreferOptionIdioms`
  * and `SuspendSideEffects`. Each is a shape the rule recognised but will not
  * rewrite (docs/RULES.md), so the instruction is the decision the reader has
  * to make.
  */
object IdiomFindings {

  val ManualResource: Finding = Finding(
    "PreferEffectIdioms",
    "manual-resource",
    "Manual acquire/release in try/finally",
    "A `finally` that closes a resource and does something else too cannot have the close lifted out mechanically, so the acquire/release stays invisible to the effect system.",
    "Reduce the `finally` to the close alone so `Using.resource` applies, or move the body into `F` and wrap it in `Resource.make(acquire)(release)`; run `<module>.fix` again and compile.",
    "#prefereffectidioms"
  )

  val UnsafeCast: Finding = Finding(
    "PreferEffectIdioms",
    "unsafe-cast",
    "Unchecked asInstanceOf",
    "An `asInstanceOf` is a claim the compiler cannot check, so a wrong one fails at runtime instead of at compile time.",
    "Model the case in the type or pattern match on it, then compile to confirm the cast is gone.",
    "#prefereffectidioms"
  )

  val MutableReference: Finding = Finding(
    "PreferEffectIdioms",
    "mutable-reference",
    "AtomicReference in effectful code",
    "An `AtomicReference` threading state through an effectful body is a `cats.effect.Ref` in disguise, and rewriting it means lifting every use into `F`.",
    "Replace the `AtomicReference` with a `Ref[F, A]` created once in the resource scope and update it through `Ref.update`/`modify`; then compile.",
    "#prefereffectidioms"
  )

  val ThrowingLookup: Finding = Finding(
    "PreferOptionIdioms",
    "throwing-lookup",
    "Lookup that throws on a missing key",
    "`getOrElse(key, throw ...)` is a partial lookup wearing a total signature, so the caller cannot see that the key may be absent.",
    "Return `Option` from the lookup and let the caller decide what a missing key means; then compile and fix the call sites.",
    "#preferoptionidioms"
  )

  val UnsuspendedEffect: Finding = Finding(
    "SuspendSideEffects",
    "unsuspended-effect",
    "Side effect the signature does not mention",
    "A method whose declared result is not an effect but whose body touches the world runs the effect when it is called, so nothing can sequence, retry or defer it.",
    "Move the method to `F[A]` under `Sync` and wrap the body in `Sync[F].delay`, or mark a realtime path with `// purrism:keep <reason>`; then update the call sites and compile.",
    "#suspendsideeffects"
  )

  val findings: List[Finding] = List(
    ManualResource,
    UnsafeCast,
    MutableReference,
    ThrowingLookup,
    UnsuspendedEffect
  )
}
