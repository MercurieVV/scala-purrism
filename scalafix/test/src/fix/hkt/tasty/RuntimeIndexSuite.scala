package fix.hkt.tasty

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.duration.*

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
}
