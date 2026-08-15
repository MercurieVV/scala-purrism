//> using scala 3.3.4
//> using dep org.scalameta::mdoc:2.9.0
//> using dep org.typelevel::cats-effect:3.7.0
//> using dep org.typelevel::laika-io:1.3.2

package docs

import java.nio.file.Files
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import laika.ast.Path.Root
import laika.api.Transformer
import laika.format.HTML
import laika.format.Markdown
import laika.helium.Helium
import laika.helium.config.TextLink
import laika.io.model.InputTree
import laika.io.syntax.*

object DocsMain:
  def main(args: Array[String]): Unit =
    val mdocOut = Paths.get("website", "docs")
    val siteOut = Paths.get("website", "site")
    cleanDirectory(mdocOut)
    cleanDirectory(siteOut)
    val settings = mdoc
      .MainSettings()
      .withIn(Paths.get("docs"))
      .withOut(mdocOut)
      .withClasspath(System.getProperty("java.class.path"))
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if exitCode != 0 then sys.exit(exitCode)

    Files.createDirectories(siteOut)
    renderSite(mdocOut, siteOut)

  private def renderSite(
      input: java.nio.file.Path,
      output: java.nio.file.Path
  ): Unit =
    val theme = Helium.defaults.all
      .metadata(
        title = Some("scala-purrism"),
        description = Some(
          "Scalafix semantic rules for refactoring Typelevel Scala toward pure, polymorphic Cats style."
        ),
        language = Some("en")
      )
      .site
      .topNavigationBar(homeLink =
        TextLink.internal(Root / "index.md", "scala-purrism")
      )
      .site
      .mainNavigation(depth = 4, includePageSections = true)
      .site
      .pageNavigation(
        enabled = true,
        depth = 4,
        sourceBaseURL = Some(
          "https://github.com/MercurieVV/scala-purrism/tree/master/docs"
        ),
        sourceLinkText = "Source",
        keepOnSmallScreens = false
      )
      .site
      .baseURL("https://mercurievv.github.io/scala-purrism/")
      .site
      .inlineCSS(purrismDiffCss)
      .build

    Transformer
      .from(Markdown)
      .to(HTML)
      .using(Markdown.GitHubFlavor)
      .withRawContent
      .parallel[IO]
      .withTheme(theme)
      .build
      .use(
        _.fromInput(
          InputTree[IO].addFile(
            input.resolve("index.md").toString,
            Root / "index.md"
          )
        )
          .toDirectory(output.toString)
          .transform
          .void
      )
      .unsafeRunSync()

  private val purrismDiffCss: String =
    """
      |pre.purrism-word-diff {
      |  background: #f6f8fa;
      |  color: #24292f;
      |  border: 1px solid #d8dee4;
      |}
      |
      |#top-bar {
      |  display: none;
      |}
      |
      |pre.purrism-word-diff code {
      |  color: inherit;
      |}
      |
      |pre.purrism-word-diff .purrism-word-diff-del,
      |pre.purrism-word-diff .purrism-word-diff-ins {
      |  border-radius: 3px;
      |  padding: 0 2px;
      |}
      |
      |pre.purrism-word-diff .purrism-word-diff-del {
      |  background: rgba(207, 34, 46, 0.16);
      |  color: #82071e;
      |  text-decoration: line-through;
      |}
      |
      |pre.purrism-word-diff .purrism-word-diff-ins {
      |  background: rgba(46, 160, 67, 0.18);
      |  color: #116329;
      |}
      |
      |pre.purrism-word-diff .purrism-token-string {
      |  color: #0a3069;
      |}
      |
      |pre.purrism-word-diff .purrism-token-comment {
      |  color: #57606a;
      |  font-style: italic;
      |}
      |
      |pre.purrism-word-diff .purrism-token-keyword {
      |  color: #bf191e;
      |  font-weight: 600;
      |}
      |
      |pre.purrism-word-diff .purrism-token-type {
      |  color: #6e40c9;
      |}
      |
      |pre.purrism-word-diff .purrism-token-number {
      |  color: #0550ae;
      |}
      |
      |@media (prefers-color-scheme: dark) {
      |  pre.purrism-word-diff {
      |    background: #1f2428;
      |    color: #e6edf3;
      |    border-color: #3d444d;
      |  }
      |
      |  pre.purrism-word-diff .purrism-word-diff-del {
      |    background: rgba(248, 81, 73, 0.22);
      |    color: #ffb8b8;
      |  }
      |
      |  pre.purrism-word-diff .purrism-word-diff-ins {
      |    background: rgba(63, 185, 80, 0.22);
      |    color: #b7f7c0;
      |  }
      |
      |  pre.purrism-word-diff .purrism-token-string {
      |    color: #a5d6ff;
      |  }
      |
      |  pre.purrism-word-diff .purrism-token-comment {
      |    color: #b1bac4;
      |  }
      |
      |  pre.purrism-word-diff .purrism-token-keyword {
      |    color: #ffa198;
      |  }
      |
      |  pre.purrism-word-diff .purrism-token-type {
      |    color: #d2a8ff;
      |  }
      |
      |  pre.purrism-word-diff .purrism-token-number {
      |    color: #79c0ff;
      |  }
      |}
      |""".stripMargin

  private def cleanDirectory(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      Files
        .walk(path)
        .iterator()
        .asScala
        .toSeq
        .reverse
        .foreach(Files.delete)
