package purrism.catsindex

import fix.prefercats._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.meta._

/** Regenerates `scalafix/resources/purrism/cats-index-<version>.ndjson.gz` from
  * the pinned cats-core sources jar. Milestone-1 subset only (#96 §5):
  * `Functor#void`, `Functor#as`, `Apply#productR`, `Apply#productL`,
  * `Foldable#foldMap`, `Traverse#sequence` -- chosen as the smallest set that
  * forces every stage of the pipeline (parse -> resolve -> normalize -> render
  * metadata) to exist.
  *
  * Invoked via `mill catsIndex.generate`, not run directly: args are
  * `<catsCoreVersion> <outputPath> [sourcesJarPath]`, where `sourcesJarPath` is
  * optional (a fresh copy is downloaded from Maven Central otherwise).
  */
object GenerateCatsIndex:

  private final case class Spec(
      owner: String,
      method: String,
      ownerKind: OwnerKind,
      renderKind: RenderKind,
      template: String,
      requiredImports: List[String]
  )

  // The smallest subset proving the general pipeline end to end (#96 §5).
  // Render templates use each function's idiomatic call form, which is not
  // always its own def name (`productR`/`productL` are exposed as the
  // operator aliases `*>`/`<*` in cats.syntax.apply) -- this is per-function
  // metadata the generator cannot derive without deeper (out-of-scope)
  // resolution, so it is recorded explicitly rather than guessed.
  private val milestone1: List[Spec] = List(
    Spec(
      "Functor",
      "void",
      OwnerKind.Typeclass,
      RenderKind.Postfix,
      "$recv.void",
      List("cats.syntax.functor.*")
    ),
    Spec(
      "Functor",
      "as",
      OwnerKind.Typeclass,
      RenderKind.Postfix,
      "$recv.as($a0)",
      List("cats.syntax.functor.*")
    ),
    Spec(
      "Apply",
      "productR",
      OwnerKind.Typeclass,
      RenderKind.Operator,
      "$recv *> $a0",
      List("cats.syntax.apply.*")
    ),
    Spec(
      "Apply",
      "productL",
      OwnerKind.Typeclass,
      RenderKind.Operator,
      "$recv <* $a0",
      List("cats.syntax.apply.*")
    ),
    Spec(
      "Foldable",
      "foldMap",
      OwnerKind.Typeclass,
      RenderKind.Postfix,
      "$recv.foldMap($a0)",
      List("cats.syntax.foldable.*")
    ),
    Spec(
      "Traverse",
      "sequence",
      OwnerKind.Typeclass,
      RenderKind.Postfix,
      "$recv.sequence",
      List("cats.syntax.traverse.*")
    )
  )

  private val neededFiles = Set(
    "cats/Functor.scala",
    "cats/Apply.scala",
    "cats/Foldable.scala",
    "cats/Traverse.scala"
  )

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

  private def extractFiles(
      jarBytes: Array[Byte],
      names: Set[String]
  ): Map[String, String] =
    val zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))
    val found = Map.newBuilder[String, String]
    var entry = zis.getNextEntry
    while entry != null do
      if names.contains(entry.getName) then
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
    val missing = names -- result.keySet
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"sources jar is missing expected files: $missing"
      )
    result

  private def traitStats(source: String, traitName: String): List[Stat] =
    val tree = source.parse[Source].get
    tree.collect { case t: Defn.Trait if t.name.value == traitName => t } match
      case t :: _ => t.templ.stats
      case Nil =>
        throw new IllegalStateException(
          s"trait $traitName not found in sources"
        )

  private def defParams(stats: List[Stat], name: String): List[String] =
    stats
      .collectFirst {
        case d: Defn.Def if d.name.value == name =>
          d.paramClauses.flatten.map(_.name.value).toList
      }
      .getOrElse(throw new IllegalStateException(s"def $name not found"))

  private def defOf(stats: List[Stat], name: String): Defn.Def =
    stats
      .collectFirst { case d: Defn.Def if d.name.value == name => d }
      .getOrElse(
        throw new IllegalStateException(s"def $name not found (or is abstract)")
      )

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
      isImplicit = p.mods.exists(_.is[Mod.Implicit]),
      hasDefault = p.default.isDefined
    )

  /** A constraint per implicit param whose type is `SomeTypeclass[...]`, e.g.
    * `implicit B: Monoid[B]` -> `"cats/Monoid#"`.
    */
  private def constraintsOf(d: Defn.Def): List[String] =
    d.paramClauses.flatten
      .collect {
        case p if p.mods.exists(_.is[Mod.Implicit]) =>
          p.decltpe match
            case Some(Type.Apply(Type.Name(tc), _)) => s"cats/$tc#"
            case Some(other)                        => s"${other.toString}#"
            case None                               => ""
      }
      .filter(_.nonEmpty)
      .toList

  private def buildCatsFn(
      spec: Spec,
      stats: List[Stat],
      members: Normalizer.MemberTable
  ): CatsFn =
    val d = defOf(stats, spec.method)
    val ir =
      Normalizer.normalize(d.body, defParams(stats, spec.method), members)
    val valueParams = d.paramClauses.flatten.map(paramSig).toList
    CatsFn(
      symbol = s"cats/${spec.owner}#${spec.method}().",
      owner = s"cats/${spec.owner}#",
      ownerKind = spec.ownerKind,
      typeParams = d.tparams.map(typeParamSig),
      valueParams = valueParams,
      returnType = d.decltpe.map(_.toString).getOrElse(""),
      constraints = constraintsOf(d),
      requiredImports = spec.requiredImports,
      render = Some(RenderTemplate(spec.renderKind, spec.template)),
      body = ir,
      hash = IR.hash(ir)
    )

  def generateDocument(catsCoreVersion: String, jarBytes: Array[Byte]): String =
    if catsCoreVersion != CatsIndex.expectedCatsCoreVersion then
      throw new IllegalStateException(
        s"requested generation for cats-core $catsCoreVersion but CatsIndex.expectedCatsCoreVersion is " +
          s"${CatsIndex.expectedCatsCoreVersion} -- update both together"
      )
    val files = extractFiles(jarBytes, neededFiles)
    val functorStats = traitStats(files("cats/Functor.scala"), "Functor")
    val applyStats = traitStats(files("cats/Apply.scala"), "Apply")
    val foldableStats = traitStats(files("cats/Foldable.scala"), "Foldable")
    val traverseStats = traitStats(files("cats/Traverse.scala"), "Traverse")

    // Free-name resolution tables, scoped per owner's own extends chain
    // (Apply extends Functor; Traverse extends Functor with Foldable) so a
    // same-named override on an unrelated trait (e.g. Traverse's own `map`)
    // can never shadow the name a different owner's body actually resolves
    // against.
    val functorMembers = Normalizer.ownDefMembers("Functor", functorStats)
    val applyMembers =
      functorMembers ++ Normalizer.ownDefMembers("Apply", applyStats)
    val foldableMembers = Normalizer.ownDefMembers("Foldable", foldableStats)
    val traverseMembers =
      functorMembers ++ foldableMembers ++ Normalizer.ownDefMembers(
        "Traverse",
        traverseStats
      )

    val statsByOwner = Map(
      "Functor" -> functorStats,
      "Apply" -> applyStats,
      "Foldable" -> foldableStats,
      "Traverse" -> traverseStats
    )
    val membersByOwner =
      Map(
        "Functor" -> functorMembers,
        "Apply" -> applyMembers,
        "Foldable" -> foldableMembers,
        "Traverse" -> traverseMembers
      )

    val fns = milestone1.map(spec =>
      buildCatsFn(spec, statsByOwner(spec.owner), membersByOwner(spec.owner))
    )
    CatsIndex.render(catsCoreVersion, fns)
