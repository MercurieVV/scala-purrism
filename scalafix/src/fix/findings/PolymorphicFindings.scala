package fix.findings

import fix.Finding

/** Finding kinds of the two signature-widening rules,
  * `PreferPolymorphicCollections` and `PreferPolymorphicTypeclasses`. Both
  * decline for overlapping reasons (a call site that names type arguments, a
  * def handed over as a value, no free type parameter name), so the kinds
  * repeat across the two rules with the rule's own subject in the prose.
  */
object PolymorphicFindings {

  // `final val` literals are constant-folded, so neither nested object reaches
  // back into the outer one while initialising -- which would cycle through
  // `findings` below.
  object Collections {
    private final val Rule = "PreferPolymorphicCollections"
    private final val Doc = "#polymorphic-signatures"

    val ExplicitTypeArguments: Finding = Finding(
      Rule,
      "explicit-type-arguments",
      "Container def called with explicit type arguments",
      "A call site names this def's type arguments and inference cannot replace them, so the def cannot take another type parameter.",
      "Let the call sites infer the type arguments, or leave the signature concrete; then re-run `<module>.fix`.",
      Doc
    )

    val HandedOverAsValue: Finding = Finding(
      Rule,
      "handed-over-as-value",
      "Container def handed over as a value",
      "The def is referenced as a value rather than called, and a polymorphic method has no monomorphic function type, so widening it would stop that reference compiling.",
      "Call the def at the use site instead of passing it as a value, or leave the signature concrete.",
      Doc
    )

    val OrderOrIndexSpecific: Finding = Finding(
      Rule,
      "order-or-index-specific",
      "Container used by position",
      "The body reaches an element by position or order -- `xs(i)`, `.indices`, `.head`, `.sorted` -- which expresses random access, and Cats has no typeclass for that.",
      "Express the body through `Foldable`/`Traverse` operations (`foldLeft`, `get`, `traverse`) or keep the concrete collection.",
      Doc
    )

    val NoCapability: Finding = Finding(
      Rule,
      "no-capability",
      "No Cats capability covers the container's use",
      "No Cats typeclass in the index covers how the body uses the container, so there is no weaker signature to widen to.",
      "Keep the concrete collection, or rewrite the body in terms of `Foldable`/`Functor`/`Traverse` operations and re-run `<module>.fix`.",
      Doc
    )

    val ContainerNotAbstract: Finding = Finding(
      Rule,
      "container-not-abstract",
      "Container does not stay abstract",
      "An operation on the container is not covered by the solved constraints, or its value is passed to a signature that names a concrete container, so the widened body would not compile.",
      "Widen the receiving signature too, or replace the uncovered operation with one the constraint provides; then re-run `<module>.fix`.",
      Doc
    )

    val NameConflict: Finding = Finding(
      Rule,
      "name-conflict",
      "No free type parameter name",
      "Every candidate name for the new type parameter (`S`, `C`, `G`) is already taken in the definition, so the rule has nothing to introduce.",
      "Rename one of the existing type parameters to free a candidate name, then re-run `<module>.fix`.",
      Doc
    )

    val RewriteOff: Finding = Finding(
      Rule,
      "rewrite-off",
      "Container abstractable but rewriting is off",
      "The container is abstractable over the reported constraints, but `PreferPolymorphicCollections.rewrite = false` leaves the signature unchanged.",
      "Widen the signature by hand to `S[_]` with the reported constraints, or set `rewrite = true` and re-run `<module>.fix`.",
      Doc
    )

    val UnsupportedKind: Finding = Finding(
      Rule,
      "unsupported-kind",
      "Unsupported type-constructor kind",
      "The container's type constructor has a kind the rule does not abstract over, such as the binary `Map[K, V]`.",
      "Keep the concrete type, or widen only the unary containers in the signature.",
      Doc
    )

    val TooManyConstraints: Finding = Finding(
      Rule,
      "too-many-constraints",
      "Widening needs too many constraints",
      "Widening the container needs more typeclass constraints than `maxConstraints` allows, so the signature would be busier than the concrete one.",
      "Raise `PreferPolymorphicCollections.maxConstraints`, or split the body so each def needs fewer capabilities; then re-run `<module>.fix`.",
      Doc
    )

    val findings: List[Finding] = List(
      ExplicitTypeArguments,
      HandedOverAsValue,
      OrderOrIndexSpecific,
      NoCapability,
      ContainerNotAbstract,
      NameConflict,
      RewriteOff,
      UnsupportedKind,
      TooManyConstraints
    )
  }

  object Typeclasses {
    private final val Rule = "PreferPolymorphicTypeclasses"
    private final val Doc = "#polymorphic-signatures"

    val ExplicitTypeArguments: Finding = Finding(
      Rule,
      "constructor-explicit-type-arguments",
      "Def called with explicit type arguments",
      "A call site names this def's type arguments and inference cannot replace them, so the def cannot take another type parameter.",
      "Let the call sites infer the type arguments, or leave the signature concrete; then re-run `<module>.fix`.",
      Doc
    )

    val PublicBoundary: Finding = Finding(
      Rule,
      "public-boundary",
      "Public API boundary kept concrete",
      "The def is public and `PreferPolymorphicTypeclasses.widenPublic = false`, so its signature is a boundary the rule does not change.",
      "Make the def private, or set `widenPublic = true` if the call sites can absorb the new type parameter; then re-run `<module>.fix`.",
      Doc
    )

    val HandedOverAsValue: Finding = Finding(
      Rule,
      "constructor-handed-over-as-value",
      "Def handed over as a value",
      "The def is referenced as a value rather than called, and a polymorphic method has no monomorphic function type, so widening it would stop that reference compiling.",
      "Call the def at the use site instead of passing it as a value, or leave the signature concrete.",
      Doc
    )

    val RewriteOff: Finding = Finding(
      Rule,
      "constructor-rewrite-off",
      "Constructor abstractable but rewriting is off",
      "The type constructor is abstractable over the reported constraints, but `PreferPolymorphicTypeclasses.rewrite = false` leaves the signature unchanged.",
      "Widen the signature by hand to `G[_]` with the reported constraints, or set `rewrite = true` and re-run `<module>.fix`.",
      Doc
    )

    val NameConflict: Finding = Finding(
      Rule,
      "constructor-name-conflict",
      "No free type parameter name",
      "Every candidate name for the new type parameter (`G`, `H`, `K`) is already taken in the definition, so the rule has nothing to introduce.",
      "Rename one of the existing type parameters to free a candidate name, then re-run `<module>.fix`.",
      Doc
    )

    val UnsafeBody: Finding = Finding(
      Rule,
      "unsafe-body",
      "Body the widening cannot carry",
      "The body throws, returns early, mutates a variable, or calls a method with no typeclass counterpart, so the constructor cannot be abstracted.",
      "Remove the obstacle -- return a value instead of throwing or mutating, or replace the call -- and re-run `<module>.fix`.",
      Doc
    )

    val ConcreteConstructorMatch: Finding = Finding(
      Rule,
      "concrete-constructor-match",
      "Pattern match on a concrete constructor",
      "The body matches on the constructor's own cases, such as `Nil` or `Some`, which fixes the concrete type and cannot be expressed through a typeclass.",
      "Replace the match with a `fold`-style operation from the typeclass, or keep the concrete type.",
      Doc
    )

    val OrderOrIndexSpecific: Finding = Finding(
      Rule,
      "constructor-order-or-index-specific",
      "Constructor used by position",
      "The body reaches an element by position or order -- `xs(i)`, `.indices`, `.head`, `.sorted` -- which expresses random access, and Cats has no typeclass for that.",
      "Express the body through `Foldable`/`Traverse` operations (`foldLeft`, `get`, `traverse`) or keep the concrete type.",
      Doc
    )

    val AmbiguousCapability: Finding = Finding(
      Rule,
      "ambiguous-capability",
      "Operations with unrelated capability roots",
      "The body uses operations whose Cats capabilities have unrelated roots, so no single weakest typeclass covers them.",
      "Split the body so each def needs one capability family, or keep the concrete type.",
      Doc
    )

    val findings: List[Finding] = List(
      ExplicitTypeArguments,
      PublicBoundary,
      HandedOverAsValue,
      RewriteOff,
      NameConflict,
      UnsafeBody,
      ConcreteConstructorMatch,
      OrderOrIndexSpecific,
      AmbiguousCapability
    )
  }

  val findings: List[Finding] = Collections.findings ++ Typeclasses.findings
}
