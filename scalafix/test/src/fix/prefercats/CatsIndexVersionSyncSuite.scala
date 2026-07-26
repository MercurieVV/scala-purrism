package fix.prefercats

import java.nio.file.{Files, Path, Paths}

/** `CatsIndex.expectedCatsCoreVersion` is a plain Scala constant duplicating
  * `Versions.catsCore` in build.mill (Scala source compiled into the rule jar
  * cannot read a Mill build script at runtime, see #96 §1). This test is the
  * drift guard for that duplication: if someone bumps one without the other,
  * this fails loudly instead of the index silently pinning to a stale cats-core
  * version.
  */
final class CatsIndexVersionSyncSuite extends munit.FunSuite {

  /** Searches upward from the test process's working directory for the
    * repo-root `build.mill`, since a forked test runner's cwd is not guaranteed
    * to be the workspace root.
    */
  private def findBuildMill(from: Path): Option[Path] =
    Iterator
      .iterate(from)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("build.mill"))
      .find(Files.exists(_))

  test(
    "CatsIndex.expectedCatsCoreVersion matches Versions.catsCore in build.mill"
  ) {
    val buildMill =
      findBuildMill(Paths.get(sys.props("user.dir")).toAbsolutePath)
    assume(
      buildMill.isDefined,
      s"build.mill not found by searching up from ${sys.props("user.dir")}"
    )
    val text = Files.readString(buildMill.get)
    val pattern = """def catsCore = "([^"]+)"""".r
    val found = pattern.findFirstMatchIn(text).map(_.group(1))
    assertEquals(found, Some(CatsIndex.expectedCatsCoreVersion))
  }
}
