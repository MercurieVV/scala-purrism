package purrism.catsindex

import fix.prefercats._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.meta._
import scala.util.control.NonFatal

/** Regenerates `scalafix/resources/purrism/cats-index-<version>.ndjson.gz` from
  * the pinned cats-core sources jar.
  *
  * Every concrete `def` on every typeclass trait in the top-level `cats`
  * package is indexed, not a hand-picked subset: `PreferCatsFunctions` can only
  * ever rewrite a body into a function this index knows, so the index *is* the
  * rule's vocabulary. A def is indexed when its body normalizes (§2) and a call
  * form can be rendered for it; anything else is skipped, since an entry with
  * no render template can only ever produce a D2 decline.
  *
  * Invoked via `mill catsIndex.generate`, not run directly: args are
  * `<catsCoreVersion> <outputPath> [sourcesJarPath]`, where `sourcesJarPath` is
  * optional (a fresh copy is downloaded from Maven Central otherwise).
  */
object GenerateCatsIndex:

  /** Call forms that are not `$recv.name(args)`.
    *
    * A typeclass method's idiomatic call form is usually its own name via
    * `cats.syntax`, which [[deriveRender]] handles. These are the ones where it
    * is not -- `productR`/`productL` are exposed as `*>`/`<*`, `sequence` takes
    * no argument at the call site -- and the generator has no way to derive
    * that from the source alone, so it is recorded explicitly.
    */
  private val renderOverrides: Map[(String, String), RenderTemplate] = Map(
    ("Apply", "productR") -> RenderTemplate(
      RenderKind.Operator,
      "$recv *> $a0"
    ),
    ("Apply", "productL") -> RenderTemplate(
      RenderKind.Operator,
      "$recv <* $a0"
    ),
    ("FlatMap", "productREval") ->
      RenderTemplate(RenderKind.Postfix, "$recv.productREval($a0)"),
    ("FlatMap", "productLEval") ->
      RenderTemplate(RenderKind.Postfix, "$recv.productLEval($a0)")
  )

  /** Methods that must never be rewritten to, whatever their body normalizes
    * to.
    *
    * `map`/`flatMap`/`pure` and friends are the primitives every other body is
    * expressed in, so a candidate matching one of them is already written the
    * idiomatic way -- rewriting `fa.map(f)` to `fa.map(f)` is a no-op patch,
    * and rewriting to the typeclass-summoner form is a regression.
    */
  private val excludedMethods: Set[String] =
    Set("map", "flatMap", "pure", "ap", "product", "flatten", "coflatMap")

  /** `cats/Foo.scala`, not `cats/data/Foo.scala`: the top-level package is
    * where the typeclasses live, and a data type's own methods are not
    * reachable through the `cats.syntax` call forms this index renders.
    */
  private val TopLevelCatsFile = """^cats/([A-Za-z0-9]+)\.scala$""".r

  private val IdentifierName = """^[a-zA-Z_][a-zA-Z0-9_]*$""".r

  def main(args: Array[String]): Unit =
    args.toList match
      case catsCoreVersion :: outputPath :: rest =>
        val jarBytes = rest match
          case sourcesJarPath :: _ =>
            Files.readAllBytes(Paths.get(sourcesJarPath))
          case Nil => download(sourcesJarUrl(catsCoreVersion))
        val doc = generateDocument(catsCoreVersion, jarBytes)
        val out = Paths.get(outputPath)
        Files.createDirectories(out.getParent)
        val tmp = Files.createTempFile(out.getParent, "cats-index-", ".gz.tmp")
        Files.write(tmp, CatsIndex.gzip(doc))
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        println(s"[catsIndex] wrote $out")
      case _ =>
        throw new IllegalArgumentException(
          "usage: GenerateCatsIndex <catsCoreVersion> <outputPath> [sourcesJarPath]"
        )

  def sourcesJarUrl(version: String): String =
    s"https://repo1.maven.org/maven2/org/typelevel/cats-core_3/$version/cats-core_3-$version-sources.jar"

  def download(url: String): Array[Byte] =
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if response.statusCode() != 200 then
      throw new IllegalStateException(
        s"failed to download $url: HTTP ${response.statusCode()}"
      )
    response.body()

  /** Every top-level `cats/<Name>.scala` entry, keyed by its jar path. */
  private def extractTopLevelFiles(
      jarBytes: Array[Byte]
  ): Map[String, String] =
    val zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))
    val found = Map.newBuilder[String, String]
    var entry = zis.getNextEntry
    while entry != null do
      if TopLevelCatsFile.matches(entry.getName) then
        val baos = new ByteArrayOutputStream()
        val buf = new Array[Byte](8192)
        var n = zis.read(buf)
        while n >= 0 do
          baos.write(buf, 0, n)
          n = zis.read(buf)
        found += entry.getName -> baos.toString("UTF-8")
      zis.closeEntry()
      entry = zis.getNextEntry
    zis.close()
    val result = found.result()
    if result.isEmpty then
      throw new IllegalStateException(
        "sources jar contains no top-level cats/*.scala files"
      )
    result

  /** One typeclass trait: its own template body plus the names it extends. */
  private final case class TraitDecl(
      name: String,
      stats: List[Stat],
      parents: List[String]
  )

  private def parentNames(t: Defn.Trait): List[String] =
    t.templ.inits
      .map(_.tpe)
      .collect {
        case Type.Name(n)                    => n
        case Type.Apply.After_4_6_0(head, _) => head.toString
      }
      .collect { case n: String => n.takeWhile(c => c != '[') }

  /** Names a body may reference as a type or companion: every top-level trait,
    * object and class in the file.
    */
  private def topLevelTypeNames(source: String): List[String] =
    source.parse[Source] match
      case parsed: Parsed.Success[Source] =>
        parsed.get.collect {
          case t: Defn.Trait  => t.name.value
          case o: Defn.Object => o.name.value
          case c: Defn.Class  => c.name.value
        }
      case _: Parsed.Error => Nil

  private def traitsIn(source: String): List[TraitDecl] =
    source.parse[Source] match
      case parsed: Parsed.Success[Source] =>
        parsed.get.collect { case t: Defn.Trait =>
          TraitDecl(t.name.value, t.templ.stats, parentNames(t))
        }
      // cats-core is not always parseable by the dialect this runs under; a
      // file that does not parse contributes nothing rather than failing the
      // whole generation.
      case _: Parsed.Error => Nil

  /** Names a Cats body can reference that are neither its own members nor
    * another Cats type: `Predef` and a few `scala` companions.
    *
    * The values are the symbols SemanticDB gives the same references on the
    * project side, which is what makes the two normalize alike. Without them a
    * body as ordinary as `traverse(fa)(identity)` fails to normalize and the
    * function never reaches the index.
    */
  private val stdlibNames: Normalizer.MemberTable = Map(
    "identity" -> "scala/Predef.identity().",
    "implicitly" -> "scala/Predef.implicitly().",
    "Some" -> "scala/Some.",
    "None" -> "scala/None.",
    "Left" -> "scala/util/Left.",
    "Right" -> "scala/util/Right.",
    "List" -> "scala/package.List.",
    "Vector" -> "scala/package.Vector.",
    "Nil" -> "scala/package.Nil."
  )

  /** Every top-level Cats type and companion, so a body may reference one by
    * name.
    *
    * `Parallel.parProductR(...)` and `Eval.now(...)` are the single largest
    * class of normalization failures -- the name resolves to nothing, the whole
    * body is discarded, and 40-odd functions go unindexed per name. On the
    * project side the same reference resolves to `cats/Parallel.`, so recording
    * it here is what lets the two sides agree.
    */
  private def catsTypeNames(names: Set[String]): Normalizer.MemberTable =
    names.map(n => n -> s"cats/$n.").toMap

  /** `name -> symbol` for everything a trait can call unqualified: its own defs
    * plus, transitively, its parents'. Parents lose to the trait's own
    * declarations, matching override resolution.
    */
  private def memberTable(
      decl: TraitDecl,
      byName: Map[String, TraitDecl]
  ): Normalizer.MemberTable =
    def go(
        current: TraitDecl,
        seen: Set[String]
    ): Normalizer.MemberTable =
      if seen.contains(current.name) then Map.empty
      else
        val inherited = current.parents
          .flatMap(byName.get)
          .foldLeft(Map.empty[String, String])((acc, parent) =>
            acc ++ go(parent, seen + current.name)
          )
        inherited ++ Normalizer.ownDefMembers(current.name, current.stats)
    go(decl, Set.empty)

  private def typeParamSig(tp: Type.Param): TypeParamSig =
    TypeParamSig(
      tp.name.value,
      tp.tparams.length,
      tp.cbounds.map(_.toString) ++ tp.vbounds.map(_.toString)
    )

  private def paramSig(p: Term.Param): ParamSig =
    ParamSig(
      name = p.name.value,
      tpe = p.decltpe.map(_.toString).getOrElse(""),
      byName =
        p.decltpe.exists { case _: Type.ByName => true; case _ => false },
      isImplicit = p.mods.exists(m => m.is[Mod.Implicit] || m.is[Mod.Using]),
      hasDefault = p.default.isDefined
    )

  /** A constraint per implicit param whose type is `SomeTypeclass[...]`, e.g.
    * `implicit B: Monoid[B]` -> `"cats/Monoid#"`.
    */
  private def constraintsOf(d: Defn.Def): List[String] =
    d.paramClauses.flatten
      .collect {
        case p if p.mods.exists(m => m.is[Mod.Implicit] || m.is[Mod.Using]) =>
          p.decltpe match
            case Some(Type.Apply.After_4_6_0(Type.Name(tc), _)) => s"cats/$tc#"
            case Some(other) => s"${other.toString}#"
            case None        => ""
      }
      .filter(_.nonEmpty)
      .toList

  /** The call form for a def, given how many explicit parameters it has.
    *
    * Slot 0 is the receiver, so a def with no explicit parameters has no call
    * form through `cats.syntax` and is not indexable.
    */
  private def deriveRender(
      owner: String,
      d: Defn.Def,
      explicitCount: Int
  ): Option[RenderTemplate] =
    renderOverrides.get((owner, d.name.value)).orElse {
      val name = d.name.value
      val argSlots = (0 until explicitCount - 1).map(i => s"$$a$i")
      if explicitCount < 1 then None
      else if IdentifierName.matches(name) then
        Some(
          RenderTemplate(
            RenderKind.Postfix,
            if argSlots.isEmpty then s"$$recv.$name"
            else s"$$recv.$name(${argSlots.mkString(", ")})"
          )
        )
      else if explicitCount == 2 then
        Some(RenderTemplate(RenderKind.Operator, s"$$recv $name $$a0"))
      else None
    }

  private def isDeprecated(d: Defn.Def): Boolean =
    d.mods.exists {
      case Mod.Annot(init) => init.tpe.toString.startsWith("deprecated")
      case _               => false
    }

  private def isIndexable(d: Defn.Def): Boolean =
    !d.mods.exists(m => m.is[Mod.Private] || m.is[Mod.Protected]) &&
      !excludedMethods.contains(d.name.value) &&
      !isDeprecated(d)

  private def catsFnOf(
      owner: String,
      d: Defn.Def,
      members: Normalizer.MemberTable
  ): Option[CatsFn] =
    val params = d.paramClauses.flatten.map(_.name.value).toList
    val valueParams = d.paramClauses.flatten.map(paramSig).toList
    val explicitCount = valueParams.count(!_.isImplicit)
    for
      render <- deriveRender(owner, d, explicitCount)
      // A body that does not normalize (unresolved free name, unsupported
      // shape) has no structural identity to match against, which is the whole
      // basis of this rule -- skip it rather than index a guess.
      // Project code calls these through `cats.syntax` (`fa.as(b)`), not as
      // unqualified sibling members (`as(fa)(b)`), so the indexed body is
      // stored in the receiver form both sides can agree on.
      ir <-
        try Some(IR.receiverize(Normalizer.normalize(d.body, params, members)))
        catch { case NonFatal(_) => None }
      // A body with no structure matches every stub in every project, and a
      // pure alias only ever renames a call. Both are indexable in principle
      // and useless (or harmful) in practice.
      if !IR.isTrivial(ir) && !IR.isAlias(ir) && !IR.containsLiteral(ir)
    yield CatsFn(
      symbol = s"cats/$owner#${d.name.value}().",
      owner = s"cats/$owner#",
      ownerKind = OwnerKind.Typeclass,
      typeParams = d.tparams.map(typeParamSig),
      valueParams = valueParams,
      returnType = d.decltpe.map(_.toString).getOrElse(""),
      constraints = constraintsOf(d),
      requiredImports = List("cats.syntax.all.*"),
      render = Some(render),
      body = ir,
      hash = IR.hash(ir)
    )

  def generateDocument(catsCoreVersion: String, jarBytes: Array[Byte]): String =
    if catsCoreVersion != CatsIndex.expectedCatsCoreVersion then
      throw new IllegalStateException(
        s"requested generation for cats-core $catsCoreVersion but CatsIndex.expectedCatsCoreVersion is " +
          s"${CatsIndex.expectedCatsCoreVersion} -- update both together"
      )

    val sources = extractTopLevelFiles(jarBytes).toList.sortBy(_._1)
    val decls = sources.flatMap((_, source) => traitsIn(source))
    val byName = decls.map(d => d.name -> d).toMap

    // Lowest-priority layer: a body's own members and its parents' always win
    // over a same-named type.
    val ambient =
      stdlibNames ++ catsTypeNames(
        sources.flatMap((_, source) => topLevelTypeNames(source)).toSet
      )

    var considered = 0
    var noRender = 0
    var noNormalize = 0

    val fns = decls.flatMap { decl =>
      val members = ambient ++ memberTable(decl, byName)
      decl.stats
        .collect { case d: Defn.Def if isIndexable(d) => d }
        .flatMap { d =>
          considered += 1
          val result = catsFnOf(decl.name, d, members)
          if result.isEmpty then
            val explicitCount =
              d.paramClauses.flatten.map(paramSig).count(!_.isImplicit)
            if deriveRender(decl.name, d, explicitCount).isEmpty then
              noRender += 1
            else noNormalize += 1
          result
        }
    }

    // Same symbol from two traits in the extends chain (an override and the
    // def it overrides) would index the same call form twice and turn every
    // match into a D1 ambiguity decline.
    val deduped = fns
      .groupBy(fn => (fn.symbol, IR.canonical(fn.body)))
      .values
      .map(_.head)
      .toList
      .sortBy(fn => (fn.symbol, fn.hash))

    println(
      s"[catsIndex] indexed ${deduped.size} cats functions from ${decls.size} traits " +
        s"($considered concrete defs considered, $noRender without a call form, " +
        s"$noNormalize whose body did not normalize)"
    )
    CatsIndex.render(catsCoreVersion, deduped)
