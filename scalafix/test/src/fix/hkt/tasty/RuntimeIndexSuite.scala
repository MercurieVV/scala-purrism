package fix.hkt.tasty

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import munit.FunSuite
import scalafix.v1.Symbol

final class RuntimeIndexSuite extends FunSuite {

  override val munitTimeout: Duration = 10.minutes

  private lazy val classpath: List[Path] =
    System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .toList
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private val Sync = Symbol("cats/effect/kernel/Sync#")
  private val Monad = Symbol("cats/Monad#")

  private def config(): CacheConfig =
    CacheConfig(Some(Files.createTempDirectory("runtime-index-suite")), false)

  test("JarLocator finds the jar holding a symbol's TASTy") {
    val found = JarLocator.jarOf(Sync, classpath)
    assert(
      found.exists(_.getFileName.toString.startsWith("cats-effect-kernel_3-")),
      found
    )
    assertEquals(JarLocator.jarOf(Symbol("no/such/Thing#"), classpath), None)
  }

  test("load closes over ancestors") {
    val index =
      RuntimeIndex.load(classpath, Set(Sync), config()).fold(fail(_), identity)
    assert(index.isAncestor(Monad, Sync))
    assert(index.depth(Sync) > index.depth(Monad))
    assert(index.capabilities.getOrElse(Sync, Nil).nonEmpty)
  }

  test("load is memoised per closure") {
    val cfg = config()
    val first =
      RuntimeIndex.load(classpath, Set(Sync), cfg).fold(fail(_), identity)
    val second =
      RuntimeIndex.load(classpath, Set(Sync), cfg).fold(fail(_), identity)
    assert(first eq second)
  }

  test("a jar that fails to scan contributes nothing; load still succeeds") {
    val broken =
      Files.createTempDirectory("runtime-index-broken").resolve("broken.jar")
    val out = new ZipOutputStream(Files.newOutputStream(broken))
    try {
      out.putNextEntry(new ZipEntry("X.tasty"))
      out.write("garbage".getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
    val index = RuntimeIndex
      .load(broken :: classpath, Set(Symbol("X#"), Sync), config())
      .fold(fail(_), identity)
    assert(index.isAncestor(Monad, Sync))
  }

  private def copyJar(from: Path, to: Path, keep: String => Boolean): Unit = {
    val zip = new ZipFile(from.toFile)
    val out = new ZipOutputStream(Files.newOutputStream(to))
    try
      zip.entries().asScala.filter(entry => keep(entry.getName)).foreach {
        entry =>
          out.putNextEntry(new ZipEntry(entry.getName))
          out.write(zip.getInputStream(entry).readAllBytes())
          out.closeEntry()
      }
    finally {
      out.close()
      zip.close()
    }
  }

  test("a jar rebuilt at the same path yields a fresh index") {
    val GenTemporal = Symbol("cats/effect/kernel/GenTemporal#")
    val kernel = classpath
      .find(_.getFileName.toString.startsWith("cats-effect-kernel_3-"))
      .getOrElse(fail("no cats-effect-kernel jar"))
    val rebuilt =
      Files.createTempDirectory("runtime-index-rebuilt").resolve("kernel.jar")
    val cp = rebuilt :: classpath.filterNot(_ == kernel)
    val cfg = config()

    copyJar(kernel, rebuilt, _ => true)
    val first = RuntimeIndex.load(cp, Set(Sync), cfg).fold(fail(_), identity)
    assert(first.typeclasses.contains(GenTemporal))

    copyJar(
      kernel,
      rebuilt,
      !_.startsWith("cats/effect/kernel/GenTemporal")
    )
    Files.setLastModifiedTime(
      rebuilt,
      FileTime.fromMillis(Files.getLastModifiedTime(rebuilt).toMillis + 10000)
    )
    val second = RuntimeIndex.load(cp, Set(Sync), cfg).fold(fail(_), identity)
    assert(first ne second)
    assert(!second.typeclasses.contains(GenTemporal))
  }

  private def rowsWithKind(kind: String): InspectedRows =
    InspectedRows(
      List(List("x/X#", "", kind, "1", "0", "X", "x.X", "true")),
      Nil,
      Nil,
      Nil,
      Nil
    )

  test("merge order: bundled source first, then by jar path") {
    val bundled =
      (
        Paths.get("/z/bundled.jar"),
        rowsWithKind("Unary"),
        Some(IndexSource.Bundled)
      )
    val scanA =
      (Paths.get("/a/a.jar"), rowsWithKind("Binary"), Some(IndexSource.Scan))
    val diskB =
      (Paths.get("/b/b.jar"), rowsWithKind("Star"), Some(IndexSource.Disk))
    def kinds(
        jars: List[(Path, InspectedRows, Option[IndexSource])]
    ): List[String] =
      RuntimeIndex.merge(jars).typeclasses.flatMap(_.lift(2))
    List(bundled, scanA, diskB).permutations.foreach(order =>
      assertEquals(kinds(order), List("Unary"), order)
    )
    List(scanA, diskB).permutations.foreach(order =>
      assertEquals(kinds(order), List("Binary"), order)
    )
  }
}
