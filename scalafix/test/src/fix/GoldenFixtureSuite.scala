package fix

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters._
import scala.meta._

final class GoldenFixtureSuite extends munit.FunSuite {
  test("golden fixtures keep one base file for each expected file") {
    val root = resourcePath("golden/typelevel")
    val baseFiles = fixtureFiles(root.resolve("base"))
    val expectedFiles = fixtureFiles(root.resolve("expected"))

    assertEquals(
      baseFiles.map(_.getFileName.toString),
      expectedFiles.map(_.getFileName.toString)
    )
    assert(baseFiles.nonEmpty, "at least one golden fixture is required")
  }

  test("golden fixtures document a concrete refactoring target") {
    val root = resourcePath("golden/typelevel")
    val base = read(root.resolve("base/01-io-service.scala"))
    val expected = read(root.resolve("expected/01-io-service.scala"))

    assert(clue(base).contains("cats.effect.IO"))
    assert(clue(expected).contains("MonadThrow"))
    assertNotEquals(base, expected)
  }

  test("rule can be discovered by name") {
    val services =
      readResources("META-INF/services/scalafix.v1.Rule").flatMap(
        _.linesIterator
      )

    assert(services.toSet.contains("fix.TypelevelPurrism"))
    assert(services.toSet.contains("fix.TypeclassWeakening"))
    assert(services.toSet.contains("fix.PreferKleisli"))
  }

  test("kleisli rewrite converts a simple unary effect method") {
    val method = firstMethod(
      """def fetch(id: String): F[User] =
        |  client.get(id)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def fetch: Kleisli[F, String, User] =
          |  Kleisli.apply { id =>
          |    client.get(id)
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite converts a public multi-parameter effect method") {
    val method = firstMethod(
      """def fetch(id: String, retry: Boolean): F[User] =
        |  client.get(id)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def fetch: Kleisli[F, (String, Boolean), User] =
          |  Kleisli.apply { case (id, retry) =>
          |    client.get(id)
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite skips recursive multi-parameter methods") {
    val method = firstMethod(
      """def fetch(id: String, retry: Int): F[User] =
        |  if (retry > 0) fetch(id, retry - 1) else client.get(id)""".stripMargin
    )

    assertEquals(PreferKleisli.kleisliRewrite(method), None)
  }

  test("kleisli rewrite converts exact recursive multi-parameter methods") {
    val method = firstMethod(
      """def acquire(root: os.Path, branchName: String): F[Unit] =
        |  cleanup(root, branchName) *> acquire(root, branchName)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def acquire: Kleisli[F, (os.Path, String), Unit] =
          |  Kleisli.apply { case input @ (root, branchName) =>
          |    cleanup(root, branchName) *> acquire(input)
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite keeps Scala 3 vararg splices") {
    val method = firstMethod(
      """def add(root: os.Path, branchName: String): F[Unit] =
        |  call(root, (Seq("git", "worktree") ++ List(branchName))*)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def add: Kleisli[F, (os.Path, String), Unit] =
          |  Kleisli.apply { case (root, branchName) =>
          |    call(root, (Seq("git", "worktree") ++ List(branchName))*)
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite wraps methods containing for expressions") {
    val method = firstMethod(
      """def release(root: os.Path, branchName: String): F[Unit] =
        |  for
        |    _ <- progress(root)
        |    _ <- call(branchName)
        |  yield ()""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def release: Kleisli[F, (os.Path, String), Unit] =
          |  Kleisli.apply { case (root, branchName) =>
          |    for
          |    _ <- progress(root)
          |    _ <- call(branchName)
          |  yield ()
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite converts a multi-parameter blocking helper") {
    val method = firstMethod(
      """private def branchExistsLocally(
        |    root: os.Path,
        |    branchName: String
        |): F[Boolean] =
        |  F.blocking {
        |    os.proc("git", "rev-parse", "--verify", branchName)
        |      .call(cwd = root, stdout = os.Pipe, stderr = os.Pipe, check = false)
        |      .exitCode == 0
        |  }""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """private def branchExistsLocally: Kleisli[F, (os.Path, String), Boolean] =
          |  Kleisli.apply { case (root, branchName) =>
          |    F.blocking {
          |    os.proc("git", "rev-parse", "--verify", branchName)
          |      .call(cwd = root, stdout = os.Pipe, stderr = os.Pipe, check = false)
          |      .exitCode == 0
          |  }
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite skips non-F unary return types") {
    val method = firstMethod(
      """def parse(id: String): Option[User] =
        |  Option.empty[User]""".stripMargin
    )

    assertEquals(PreferKleisli.kleisliRewrite(method), None)
  }

  test("kleisli rewrite composes direct Kleisli apply calls") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  fetch(id).flatMap(user => profile(user))""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method, Set("fetch", "profile")),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite composes explicit Kleisli run calls") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  fetch.run(id).flatMap(user => profile.run(user))""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite collapses a direct Kleisli alias") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  profile(id)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method, Set("profile")),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  profile""".stripMargin
      )
    )
  }

  test("kleisli rewrite collapses an explicit Kleisli run alias") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  profile.run(id)""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  profile""".stripMargin
      )
    )
  }

  test("kleisli rewrite composes inside an existing Kleisli") {
    val method = firstMethod(
      """def loadProfile: Kleisli[F, String, Profile] =
        |  Kleisli.apply { id =>
        |    fetch(id).flatMap(user => profile(user))
        |  }""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method, Set("fetch", "profile")),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite splits setup before a final Kleisli call") {
    val method = firstMethod(
      """def loadProfile: Kleisli[F, String, Profile] =
        |  Kleisli.apply { id =>
        |    val user = User(id)
        |    profile.run(user)
        |  }""".stripMargin
    )

    assertEquals(
      PreferKleisli.kleisliRewrite(method),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  profile.local { id =>
          |    val user = User(id)
          |    user
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite skips direct calls that are not known Kleislies") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  client.get(id).flatMap(user => profile.save(user))""".stripMargin
    )

    assertEquals(PreferKleisli.kleisliCompositionRewrite(method), None)
  }

  test("kleisli rule candidates skip local helper methods") {
    val source = parseSource(
      """final class PollingService[F[_]](client: Client[F]) {
        |  def fetch(id: String): F[User] =
        |    client.get(id)
        |
        |  def poll(taskId: Int): F[Issue] = {
        |    def loop(deadlineMillis: Long): F[Issue] =
        |      client.issue(taskId).flatMap {
        |        case issue if issue.closed => client.pure(issue)
        |        case _ => loop(deadlineMillis)
        |      }
        |    loop(1000L)
        |  }
        |}""".stripMargin
    )

    assertEquals(
      PreferKleisli.rewriteCandidates(source).map(_.name.value),
      List("fetch", "poll")
    )
  }

  test("kleisli rule candidates include private class helpers") {
    val source = parseSource(
      """final class Git[F[_]] {
        |  private def branchExistsLocally(
        |      root: os.Path,
        |      branchName: String
        |  ): F[Boolean] =
        |    F.blocking(false)
        |}""".stripMargin
    )

    assertEquals(
      PreferKleisli.rewriteCandidates(source).map(_.name.value),
      List("branchExistsLocally")
    )
  }

  test("kleisli rewrite plan keeps callers when tupled callees are rewritten") {
    val source = parseSource(
      """final class Git[F[_]] {
        |  def ensureBranch(root: os.Path, branchName: String): F[Boolean] =
        |    branchExistsLocally(root, branchName)
        |
        |  private def branchExistsLocally(
        |      root: os.Path,
        |      branchName: String
        |  ): F[Boolean] =
        |    F.blocking(false)
        |}""".stripMargin
    )

    assertEquals(
      PreferKleisli.rewritePlan(source).map(_._1.name.value),
      List("branchExistsLocally")
    )
  }

  test("kleisli rewrite plan skips methods used with placeholder arguments") {
    val source = parseSource(
      """final class Git[F[_]] {
        |  def acquire(root: os.Path, progress: String => F[Unit]): F[Unit] =
        |    branch.traverse_(ensureBranch(root, _, progress))
        |
        |  def ensureBranch(
        |      root: os.Path,
        |      branchName: String,
        |      progress: String => F[Unit]
        |  ): F[Unit] =
        |    progress(branchName)
        |}""".stripMargin
    )

    assertEquals(
      PreferKleisli.rewritePlan(source).map(_._1.name.value),
      List("acquire")
    )
  }

  test("kleisli rewrite plan converts exact recursive workflow methods") {
    val source = parseSource(
      """final class Git[F[_]] {
        |  def acquireWorktree(
        |      root: os.Path,
        |      worktreePath: os.Path,
        |      branchName: String,
        |      baseBranch: Option[String],
        |      progress: String => F[Unit]
        |  ): F[Unit] =
        |    release(root, worktreePath, branchName, progress) *>
        |      acquireWorktree(root, worktreePath, branchName, baseBranch, progress)
        |
        |  def release(
        |      root: os.Path,
        |      worktreePath: os.Path,
        |      branchName: String,
        |      progress: String => F[Unit],
        |      force: Boolean = true
        |  ): F[Unit] =
        |    for _ <- progress(branchName) yield ()
        |}""".stripMargin
    )

    assertEquals(
      PreferKleisli.rewritePlan(source).map(_._1.name.value),
      List("acquireWorktree")
    )
  }

  test("context-bound weakening replaces Sync with Monad for monadic usage") {
    val cls = firstClass(
      """final class UserService[F[_]: Sync] {
        |  def load(seed: F[User]): F[Profile] =
        |    seed.flatMap(user => profile(user))
        |}""".stripMargin
    )

    assertEquals(
      TypeclassWeakening
        .contextBoundWeakenings(cls)
        .map(weakening =>
          (
            weakening.originalName,
            weakening.original.syntax,
            weakening.replacement
          )
        ),
      List(("Sync", "Sync", "Monad"))
    )
  }

  test("context-bound weakening keeps Sync when effect operations are used") {
    val cls = firstClass(
      """final class UserService[F[_]: Sync] {
        |  def load(id: String): F[User] =
        |    Sync[F].delay(User(id))
        |}""".stripMargin
    )

    assertEquals(TypeclassWeakening.contextBoundWeakenings(cls), Nil)
  }

  test("context-bound weakening keeps Sync when called helper needs Sync") {
    val source = parseSource(
      """final class UserService[F[_]: Sync] {
        |  def load(seed: F[User]): F[Profile] =
        |    seed.flatMap(user => loadWithSync(user.id))
        |
        |  private def loadWithSync[F[_]: Sync](id: String): F[Profile] =
        |    Sync[F].delay(Profile(id))
        |}""".stripMargin
    )

    assertEquals(TypeclassWeakening.contextBoundWeakenings(source), Nil)
  }

  test("context-bound weakening weakens through monad-only helpers") {
    val source = parseSource(
      """final class UserService[F[_]: Sync] {
        |  def load(seed: F[Profile]): F[Profile] =
        |    seed.flatMap(profile => normalize(profile.pure[F]))
        |
        |  private def normalize[F[_]: Sync](profile: F[Profile]): F[Profile] =
        |    profile.map(identity)
        |}""".stripMargin
    )

    assertEquals(
      TypeclassWeakening
        .contextBoundWeakenings(source)
        .map(weakening =>
          (
            weakening.originalName,
            weakening.original.syntax,
            weakening.replacement
          )
        ),
      List(("Sync", "Sync", "Monad"), ("Sync", "Sync", "Monad"))
    )
  }

  test(
    "context-bound weakening keeps Sync for external effect-polymorphic calls"
  ) {
    val source = parseSource(
      """object Main {
        |  private def effectiveIssue[F[_]: Sync](
        |      root: String,
        |      issue: Issue
        |  ): F[Issue] =
        |    TaskMetadataStore
        |      .commentBased[F]
        |      .read(root, issue)
        |      .map(merged => issue.copy(body = merged))
        |}""".stripMargin
    )

    assertEquals(TypeclassWeakening.contextBoundWeakenings(source), Nil)
  }

  test(
    "context-bound weakening keeps Sync through unknown external helper calls"
  ) {
    val source = parseSource(
      """object Main {
        |  private def waitForUserInput[F[_]: Sync](seed: F[String]): F[Unit] =
        |    seed.flatMap(message => progress[F](message))
        |
        |  private def progress[F[_]: Sync](message: String): F[Unit] =
        |    TaskLogger.script(message)
        |}""".stripMargin
    )

    assertEquals(TypeclassWeakening.contextBoundWeakenings(source), Nil)
  }

  private def resourcePath(name: String): Path =
    Path.of(getClass.getClassLoader.getResource(name).toURI)

  private def fixtureFiles(dir: Path): List[Path] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(path => path.getFileName.toString.endsWith(".scala"))
      .toList
      .sortBy(_.getFileName.toString)

  private def read(path: Path): String =
    Files.readString(path, StandardCharsets.UTF_8)

  private def readResources(name: String): List[String] =
    getClass.getClassLoader.getResources(name).asScala.toList.map { url =>
      val stream = url.openStream()
      try String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    }

  private def firstMethod(source: String): Defn.Def =
    s"""final class Wrapper {
      |$source
       |}""".stripMargin
      .parse[Source]
      .get
      .stats
      .collectFirst { case cls: Defn.Class =>
        cls.templ.body.stats.collectFirst { case defn: Defn.Def => defn }.get
      }
      .get

  private def firstClass(source: String): Defn.Class =
    parseSource(source).stats.collectFirst { case cls: Defn.Class => cls }.get

  private def parseSource(source: String): Source =
    source
      .parse[Source]
      .get
}
