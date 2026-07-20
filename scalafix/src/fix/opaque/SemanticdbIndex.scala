package fix.opaque

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.meta.internal.{semanticdb => s}

/** The whole-program SemanticDB payload, read straight off disk.
  *
  * Scalafix hands a rule one file at a time and its `Symtab` only answers
  * `info(symbol)` -- there is no way to ask who *references* a symbol, and
  * `SemanticDocument.internal` is `private[scalafix]`. Cross-file call sites
  * are therefore unreachable through the scalafix API, so the graph is built by
  * parsing the `.semanticdb` protobufs directly.
  *
  * The directories come from `Configuration.scalacClasspath`, which the CLI
  * populates with `--semanticdb-targetroots` (see scalafix-cli `Args.scala`,
  * `validatedClasspath = Classpath(targetrootClasspath) ++ baseClasspath`), so
  * no extra configuration key is needed.
  */
final class SemanticdbIndex(val documents: List[s.TextDocument]) {

  /** Every symbol defined anywhere in the analysed sources. */
  val symbolInfo: Map[String, s.SymbolInformation] =
    documents.iterator
      .flatMap(doc =>
        doc.symbols
          .map(info => SemanticdbIndex.qualify(doc.uri, info.symbol) -> info)
      )
      .toMap

  /** Which document defines each symbol, for provenance and patch routing. */
  val definingUri: Map[String, String] =
    documents.iterator
      .flatMap(doc =>
        doc.symbols
          .map(info => SemanticdbIndex.qualify(doc.uri, info.symbol) -> doc.uri)
      )
      .toMap

  val documentByUri: Map[String, s.TextDocument] =
    documents.map(doc => doc.uri -> doc).toMap

  def isProject(symbol: String): Boolean = symbolInfo.contains(symbol)

  /** The type of the value a symbol produces.
    *
    * A `val` or parameter has a `ValueSignature`. A method -- whether or not it
    * takes parameters -- produces its return type, so `Node(method, root)`
    * reads as "what this method hands back". Parameters are addressed by their
    * own symbols, never by an index into the method.
    */
  def valueType(symbol: String): Option[s.Type] =
    symbolInfo.get(symbol).flatMap { info =>
      info.signature match {
        case s.ValueSignature(tpe)     => Some(tpe)
        case method: s.MethodSignature => Some(method.returnType)
        case _                         => None
      }
    }

  /** The explicit parameter symbols of a method, flattened across parameter
    * lists.
    *
    * Implicit and given parameters are dropped. A context bound such as
    * `def call[F[_]: Sync](cwd: os.Path, command: String*)` desugars to a
    * second parameter list, so flattening everything leaves the repeated
    * parameter no longer last -- the varargs check then fails and arguments
    * align against the wrong parameters.
    */
  def parameterSymbols(symbol: String): List[String] =
    symbolInfo.get(symbol).toList.flatMap { info =>
      info.signature match {
        case method: s.MethodSignature =>
          method.parameterLists
            .flatMap(_.symlinks)
            .filterNot(isImplicitParameter)
            .toList
        case _ => Nil
      }
    }

  private def isImplicitParameter(symbol: String): Boolean =
    symbolInfo.get(symbol).exists { info =>
      val implicitBit = s.SymbolInformation.Property.IMPLICIT.value
      val givenBit = s.SymbolInformation.Property.GIVEN.value
      (info.properties & (implicitBit | givenBit)) != 0
    }

  /** Resolve a `TypePath` into a signature, following type arguments. The
    * result is canonicalised so aliased spellings of one type compare equal.
    */
  def typeAt(node: Node): Option[String] = {
    def walk(tpe: s.Type, indices: List[Int]): Option[String] =
      (tpe, indices) match {
        case (s.TypeRef(_, symbol, _), Nil) =>
          Some(SemanticdbIndex.canonicalType(symbol))
        case (s.TypeRef(_, _, args), index :: rest) =>
          args.lift(index).flatMap(walk(_, rest))
        // Wrappers that do not change the value's identity.
        case (s.ByNameType(inner), _)       => walk(inner, indices)
        case (s.AnnotatedType(_, inner), _) => walk(inner, indices)
        case (s.UniversalType(_, inner), _) => walk(inner, indices)
        // A repeated parameter is deliberately NOT unwrapped at the root. A
        // `command: String*` shell argument list is not a String: it is a
        // heterogeneous sequence that happens to hold one. Treating it as a
        // String would pull `call(root, "git", "branch", branchName)` into the
        // closure, retype the varargs, and then "wrap" the literals as
        // BranchName("git"). Its element is still addressable at path [0] for
        // anyone who genuinely wants it.
        case (s.RepeatedType(inner), index :: rest) if index == 0 =>
          walk(inner, rest)
        case _ => None
      }
    valueType(node.symbol).flatMap(walk(_, node.path.indices))
  }

  /** Occurrences of `symbol` across every document, with positions. */
  def occurrencesOf(symbol: String): List[(String, s.SymbolOccurrence)] =
    documents.flatMap(doc =>
      doc.occurrences
        .filter(occurrence =>
          SemanticdbIndex.qualify(doc.uri, occurrence.symbol) == symbol
        )
        .map(doc.uri -> _)
    )

  /** A Kleisli's input slots are addressed by path rather than by parameter
    * symbol, so this reports which type argument holds the input.
    *
    * The unwrapping matters: Scala 3 emits a parameterless
    * `def foo: Kleisli[...]` as
    * `ValueSignature(ByNameType(TypeRef(Kleisli, ...)))`, so matching TypeRef
    * directly misses every Kleisli declared that way -- which is all of them in
    * the target codebase.
    */
  def functionInputArgIndex(symbol: String): Option[Int] =
    valueType(symbol).map(SemanticdbIndex.unwrapTypeWrappers).collect {
      case s.TypeRef(_, typeConstructor, args)
          if args.length >= 2 &&
            SemanticdbIndex.KleisliLike.contains(typeConstructor) =>
        1
    }

  /** Documents whose recorded md5 no longer matches the file on disk.
    *
    * A stale payload silently produces a stale graph, so callers report these
    * rather than trusting them.
    */
  def staleDocuments(sourceroot: Path): List[String] =
    documents
      .filter { doc =>
        val file = sourceroot.resolve(doc.uri)
        Files.exists(file) && SemanticdbIndex.md5(file) != doc.md5
      }
      .map(_.uri)
}

object SemanticdbIndex {

  /** Type constructors whose *second* type argument is the input a value flows
    * in through, so `Kleisli[F, (Path, String), Boolean]` addresses its String
    * at path [1, 1].
    */
  val KleisliLike: Set[String] = Set("cats/data/Kleisli#")

  /** SemanticDB local symbols (`local0`, `local1`, ...) are numbered per
    * document, so `local5` in Git.scala and `local5` in github.scala are
    * different values that share a name. Qualifying them by document uri keeps
    * a global symbol table from silently merging unrelated locals -- which
    * would fabricate flow edges between files that never touch.
    */
  def qualify(uri: String, symbol: String): String =
    if (symbol.startsWith("local")) s"$uri#$symbol" else symbol

  /** Type symbols that denote the same type under different spellings.
    *
    * `String` reaches SemanticDB as `scala/Predef.String#` in a declared
    * signature but as the dealiased `java/lang/String#` on a pattern-bound
    * local, so a value destructured out of a Kleisli tuple would otherwise look
    * like a different type from the field it came from and silently end the
    * propagation.
    */
  private val typeAliases: Map[String, String] = Map(
    "scala/Predef.String#" -> "java/lang/String#"
  )

  /** Collapse a type symbol onto its canonical spelling. */
  def canonicalType(symbol: String): String =
    typeAliases.getOrElse(symbol, symbol)

  /** Strip wrappers that do not change a value's identity. */
  def unwrapTypeWrappers(tpe: s.Type): s.Type = tpe match {
    case s.ByNameType(inner)       => unwrapTypeWrappers(inner)
    case s.AnnotatedType(_, inner) => unwrapTypeWrappers(inner)
    case s.UniversalType(_, inner) => unwrapTypeWrappers(inner)
    case other                     => other
  }

  /** Cache keyed on the resolved roots, so one run parses each payload once. */
  private val cache =
    new java.util.concurrent.ConcurrentHashMap[List[Path], SemanticdbIndex]()

  def load(roots: List[Path]): SemanticdbIndex = {
    val key = roots.map(_.toAbsolutePath.normalize).distinct.sorted
    cache.computeIfAbsent(key, _ => new SemanticdbIndex(readAll(key)))
  }

  private def readAll(roots: List[Path]): List[s.TextDocument] =
    roots
      .flatMap { root =>
        val semanticdbDir = root.resolve("META-INF").resolve("semanticdb")
        if (!Files.isDirectory(semanticdbDir)) Nil
        else {
          val stream = Files.walk(semanticdbDir)
          try
            stream
              .iterator()
              .asScala
              .filter(path => path.toString.endsWith(".semanticdb"))
              .flatMap { path =>
                try
                  s.TextDocuments.parseFrom(Files.readAllBytes(path)).documents
                catch { case _: Exception => Nil }
              }
              .toList
          finally stream.close()
        }
      }
      .distinctBy(_.uri)

  private def md5(file: Path): String = {
    val digest = java.security.MessageDigest.getInstance("MD5")
    digest.digest(Files.readAllBytes(file)).map(b => f"$b%02X").mkString
  }

  private implicit val pathOrdering: Ordering[Path] =
    Ordering.by(_.toString)
}
