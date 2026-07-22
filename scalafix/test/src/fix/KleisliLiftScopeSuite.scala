package fix

import java.nio.file.Files

import scala.meta._
import scala.meta.internal.{semanticdb => s}

import fix.opaque.SemanticdbIndex

/** Drives [[KleisliLiftScope]] with a hand-built SemanticDB payload, no
  * compiler in the loop.
  *
  * The payload is generated from the parsed source by the same rule the
  * compiler follows for these shapes: every `def` name gets a DEFINITION
  * occurrence carrying a symbol qualified by its enclosing object, and every
  * other mention of that name gets a REFERENCE occurrence carrying the same
  * symbol. That is enough to exercise what the scope actually reasons about --
  * which symbol a mention resolves to -- and it lets two objects define the
  * same name, which is the case name matching could never get right.
  */
final class KleisliLiftScopeSuite extends munit.FunSuite {

  private val Header =
    """package probe
      |
      |import cats.effect.Sync
      |""".stripMargin

  private def scopeOf(files: (String, String)*): KleisliLiftScope = {
    val root = Files.createTempDirectory("kleisli-lift-scope")
    val parsed = files.toList.map { case (name, body) =>
      val text = Header + body
      Files.write(root.resolve(name), text.getBytes("UTF-8"))
      (
        name,
        text,
        dialects.Scala3(Input.VirtualFile(name, text)).parse[Source].get
      )
    }

    // The symbol table spans every file, as the compiler's does -- a reference
    // in one file has to resolve to a definition in another, which is the whole
    // point of the cross-file scope.
    val declaredSymbols =
      parsed.flatMap { case (_, _, tree) =>
        tree.collect { case defn: Defn.Def =>
          defn.name.value -> symbolFor(defn)
        }
      }.toMap

    val documents = parsed.map { case (uri, text, tree) =>
      document(uri, text, tree, declaredSymbols)
    }

    KleisliLiftScope.build(root, new SemanticdbIndex(documents))
  }

  /** A `TextDocument` whose occurrences mirror what the compiler emits for the
    * `object Owner { def name(...) }` shapes these tests use.
    */
  private def document(
      uri: String,
      text: String,
      tree: Source,
      declaredSymbols: Map[String, String]
  ): s.TextDocument = {
    val definitions = tree.collect { case defn: Defn.Def =>
      defn.name -> symbolFor(defn)
    }

    val definitionOccurrences = definitions.map { case (name, symbol) =>
      occurrence(name.pos, symbol, s.SymbolOccurrence.Role.DEFINITION)
    }
    val referenceOccurrences = tree.collect {
      case name: Term.Name if !definitions.exists { case (defName, _) =>
            defName eq name
          } =>
        resolve(name, declaredSymbols).map(symbol =>
          occurrence(name.pos, symbol, s.SymbolOccurrence.Role.REFERENCE)
        )
    }.flatten

    s.TextDocument(
      uri = uri,
      text = text,
      occurrences = definitionOccurrences ++ referenceOccurrences
    )
  }

  /** Which declared symbol a mention refers to. `Mirror.load` names Mirror's
    * `load`; a bare `inner` names the one its own object declares. Two objects
    * declaring `load` stay distinguishable, which is the case these tests exist
    * for.
    */
  private def resolve(
      name: Term.Name,
      declaredSymbols: Map[String, String]
  ): Option[String] = {
    val qualified = name.parent.collect {
      case Term.Select(Term.Name(owner), selected) if selected eq name =>
        s"probe/$owner.${name.value}()."
    }
    val ownScope =
      enclosingObject(name).map(owner => s"probe/$owner.${name.value}().")

    qualified
      .orElse(ownScope)
      .filter(declaredSymbols.values.toSet.contains)
      .orElse(declaredSymbols.get(name.value).filter(_ => qualified.isEmpty))
  }

  private def enclosingObject(tree: Tree): Option[String] =
    tree.parent.flatMap {
      case obj: Defn.Object => Some(obj.name.value)
      case other            => enclosingObject(other)
    }

  /** `probe/Owner.name().` -- the symbol carries the enclosing object, so two
    * objects declaring `load` are two different symbols.
    */
  private def symbolFor(defn: Defn.Def): String =
    // `Template.Body` sits between a def and its template, so the owner is
    // found by walking ancestors rather than counting parents.
    s"probe/${enclosingObject(defn).getOrElse("probe")}.${defn.name.value}()."

  private def occurrence(
      pos: Position,
      symbol: String,
      role: s.SymbolOccurrence.Role
  ): s.SymbolOccurrence =
    s.SymbolOccurrence(
      range = Some(
        s.Range(pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
      ),
      symbol = symbol,
      role = role
    )

  test("a multi-parameter effectful def is offered, tupling every parameter") {
    val scope = scopeOf(
      "Store.scala" ->
        """object Store {
          |  def load[F[_]: Sync](root: String, id: Int): F[String] =
          |    Sync[F].pure(s"$root/$id")
          |}
          |""".stripMargin
    )

    assertEquals(
      scope.shapeOf("probe/Store.load()."),
      Some(LiftShape(List(false, false)))
    )
  }

  test("an effect callback stays a parameter rather than joining the input") {
    val scope = scopeOf(
      "Store.scala" ->
        """object Store {
          |  def load[F[_]: Sync](root: String, progress: String => F[Unit]): F[String] =
          |    Sync[F].pure(root)
          |}
          |""".stripMargin
    )

    assertEquals(
      scope.shapeOf("probe/Store.load()."),
      Some(LiftShape(List(false, true)))
    )
  }

  test("passing a def as a value vetoes only that symbol, not its namesake") {
    val scope = scopeOf(
      "Store.scala" ->
        """object Store {
          |  def load[F[_]: Sync](root: String, id: Int): F[String] =
          |    Sync[F].pure(s"$root/$id")
          |}
          |""".stripMargin,
      "Mirror.scala" ->
        """object Mirror {
          |  def load[F[_]: Sync](root: String, id: Int): F[String] =
          |    Sync[F].pure(root)
          |}
          |""".stripMargin,
      "Uses.scala" ->
        """object Uses {
          |  def handoff[F[_]: Sync]: ((String, Int) => F[String]) =
          |    Mirror.load
          |}
          |""".stripMargin
    )

    assertEquals(
      scope.shapeOf("probe/Store.load()."),
      Some(LiftShape(List(false, false))),
      "Store.load is only ever called, so it still lifts"
    )
    assertEquals(
      scope.shapeOf("probe/Mirror.load()."),
      None,
      "Mirror.load is handed over unapplied and must not become a Kleisli"
    )
  }

  test("a def calling another lifted def is deferred") {
    val scope = scopeOf(
      "Chain.scala" ->
        """object Chain {
          |  def inner[F[_]: Sync](root: String, id: Int): F[String] =
          |    Sync[F].pure(root)
          |
          |  def outer[F[_]: Sync](root: String, id: Int): F[String] =
          |    inner[F](root, id)
          |}
          |""".stripMargin
    )

    assertEquals(
      scope.shapeOf("probe/Chain.inner()."),
      Some(LiftShape(List(false, false)))
    )
    assertEquals(
      scope.shapeOf("probe/Chain.outer()."),
      None,
      "outer's replacement text would carry a copy of the call to inner"
    )
  }

  test("dropping a def frees the one that only called it -- the fixpoint") {
    val scope = scopeOf(
      "Chain.scala" ->
        """object Chain {
          |  def inner[F[_]: Sync](root: String, id: Int): F[String] =
          |    Sync[F].pure(root)
          |
          |  def outer[F[_]: Sync](root: String, id: Int): F[String] =
          |    inner[F](root, id)
          |}
          |""".stripMargin,
      "Uses.scala" ->
        """object Uses {
          |  def handoff[F[_]: Sync]: ((String, Int) => F[String]) =
          |    Chain.inner
          |}
          |""".stripMargin
    )

    assertEquals(
      scope.shapeOf("probe/Chain.inner()."),
      None,
      "inner is passed as a value, so it is vetoed"
    )
    assertEquals(
      scope.shapeOf("probe/Chain.outer()."),
      Some(LiftShape(List(false, false))),
      "with inner out of the running, outer no longer calls a def being re-shaped"
    )
  }

  test("a source with no liftable def yields an empty scope") {
    val scope = scopeOf(
      "Plain.scala" ->
        """object Plain {
          |  def describe(value: Int): String = value.toString
          |}
          |""".stripMargin
    )

    assert(scope.isEmpty, s"expected no candidates, got ${scope.shapes.keySet}")
  }
}
