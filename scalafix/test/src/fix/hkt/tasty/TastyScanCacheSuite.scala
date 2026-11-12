package fix.hkt.tasty

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import munit.FunSuite

final class TastyScanCacheSuite extends FunSuite {

  override val munitTimeout: Duration = 10.minutes

  private lazy val classpath: List[Path] =
    System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .toList
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private def jar(prefix: String): Path =
    classpath
      .find(path =>
        path.getFileName.toString.startsWith(prefix) &&
          path.getFileName.toString.endsWith(".jar")
      )
      .getOrElse(fail(s"no jar on test classpath with prefix $prefix"))

  private def tempDir(): Path =
    Files.createTempDirectory("tasty-scan-cache-suite")

  private def entries(dir: Path): List[String] =
    if (!Files.exists(dir)) Nil
    else
      Files.list(dir).iterator().asScala.map(_.getFileName.toString).toList

  private def config(dir: Path): CacheConfig = CacheConfig(Some(dir), false)

  test("cache miss scans then hits disk") {
    val dir = tempDir()
    val kernel = jar("cats-effect-kernel_3-")
    val coldStart = System.nanoTime()
    val first = TastyScanCache.rows(kernel, classpath, config(dir))
    val coldMs = (System.nanoTime() - coldStart) / 1000000
    val diskStart = System.nanoTime()
    val second = TastyScanCache.rows(kernel, classpath, config(dir))
    val diskMs = (System.nanoTime() - diskStart) / 1000000
    println(
      s"TIMING cats-effect-kernel cold-scan=${coldMs}ms disk-hit=${diskMs}ms"
    )
    val (firstRows, firstSource) = first.fold(fail(_), identity)
    val (secondRows, secondSource) = second.fold(fail(_), identity)
    assertEquals(firstSource, IndexSource.Scan)
    assertEquals(secondSource, IndexSource.Disk)
    assertEquals(secondRows, firstRows)
    assert(firstRows.typeclasses.nonEmpty)
  }

  test("per-jar rows keep parents that live in another jar") {
    val (rows, _) = TastyScanCache
      .rows(jar("cats-effect-kernel_3-"), classpath, CacheConfig(None, false))
      .fold(fail(_), identity)
    val sync = rows.typeclasses
      .find(_.headOption.contains("cats/effect/kernel/Sync#"))
      .getOrElse(fail("Sync missing"))
    val parents = sync.lift(1).toList.flatMap(_.split(",").toList)
    assert(parents.contains("cats/Defer#"), parents)
    assert(parents.contains("cats/effect/kernel/MonadCancel#"), parents)
    assert(
      rows.capabilities.exists(row =>
        row.headOption.contains("cats/effect/kernel/Sync#") &&
          row.lift(2).exists(_.startsWith("cats/FlatMap#flatMap"))
      ),
      "inherited cats-core capability owner dropped"
    )
  }

  test("bundled cats jar never scans") {
    val dir = tempDir()
    val result =
      TastyScanCache.rows(jar("cats-core_3-"), classpath, config(dir))
    val (rows, source) = result.fold(fail(_), identity)
    assertEquals(source, IndexSource.Bundled)
    assert(rows.typeclasses.exists(_.headOption.contains("cats/Monad#")))
    assertEquals(entries(dir), Nil)
    val (_, kernelSource) = TastyScanCache
      .rows(jar("cats-kernel_3-"), classpath, config(dir))
      .fold(fail(_), identity)
    assertEquals(kernelSource, IndexSource.Bundled)
  }

  test("scan of unreadable jar yields Left") {
    val dir = tempDir()
    val broken = tempDir().resolve("broken.jar")
    val out = new ZipOutputStream(Files.newOutputStream(broken))
    try {
      out.putNextEntry(new ZipEntry("X.tasty"))
      out.write("not tasty at all".getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
    val result = TastyScanCache.rows(broken, classpath, config(dir))
    assert(result.isLeft, result)
    assertEquals(entries(dir).filterNot(_.contains(".tmp-")), Nil)
  }

  test("concurrent writers leave a complete entry") {
    val dir = tempDir()
    val kernel = jar("cats-effect-kernel_3-")
    val results = Await.result(
      Future.sequence(
        List.fill(8)(
          Future(TastyScanCache.rows(kernel, classpath, config(dir)))
        )
      ),
      9.minutes
    )
    val rows = results.map(_.fold(fail(_), identity)._1)
    assertEquals(rows.distinct.size, 1)
    assertEquals(entries(dir).filter(_.contains(".tmp-")), Nil)
    assertEquals(entries(dir), List(TastyScanCache.key(kernel)))
    val (reread, source) =
      TastyScanCache
        .rows(kernel, classpath, config(dir))
        .fold(fail(_), identity)
    assertEquals(source, IndexSource.Disk)
    assertEquals(reread, rows.head)
  }

  test("key changes with mtime") {
    val copy = tempDir().resolve("copy.jar")
    Files.copy(
      jar("cats-effect-kernel_3-"),
      copy,
      StandardCopyOption.COPY_ATTRIBUTES
    )
    val before = TastyScanCache.key(copy)
    Files.setLastModifiedTime(
      copy,
      FileTime.fromMillis(Files.getLastModifiedTime(copy).toMillis + 10000)
    )
    assertNotEquals(TastyScanCache.key(copy), before)
    assertEquals(TastyScanCache.key(copy), TastyScanCache.key(copy))
  }
}
