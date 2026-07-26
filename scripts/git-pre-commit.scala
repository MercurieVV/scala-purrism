#!/usr/bin/env scala-cli

//> using scala 3.3.4
//> using dep com.lihaoyi::os-lib:0.11.8

import os._

// Pre-commit auto-fixes code (scalafmt, scalafix) then compiles it (scalac).
// Fixes are re-staged for whichever of the changed files were already staged,
// so the commit picks them up automatically. Compile errors are not
// auto-fixable and abort the commit.
object GitPreCommit:
  def main(args: Array[String]): Unit =
    val repoRoot = os.Path(
      os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim
    )

    val buildTool =
      if os.exists(repoRoot / "build.sbt") then "sbt"
      else if os.exists(repoRoot / "build.mill") || os.exists(
          repoRoot / "build.sc"
        )
      then "mill"
      else "scala-cli"

    val hasScalafmt = os.exists(repoRoot / ".scalafmt.conf")
    val hasScalafix = os.exists(repoRoot / ".scalafix.conf")

    if !hasScalafmt && !hasScalafix then
      println(
        "No formatting or linting configuration found. Pre-commit check skipped."
      )
      sys.exit(0)

    println("=== Git Pre-Commit Auto-Fix ===")

    val stagedFiles = os
      .proc("git", "diff", "--cached", "--name-only", "--diff-filter=ACM")
      .call(cwd = repoRoot)
      .out
      .lines()
      .toList

    if hasScalafmt then
      println("Formatting code (Scalafmt)...")
      val fmtExit = buildTool match
        case "sbt" =>
          os.proc("sbt", "scalafmtAll").call(cwd = repoRoot, check = false).exitCode
        case "scala-cli" =>
          os.proc("scala-cli", "fmt", ".").call(cwd = repoRoot, check = false).exitCode
        case "mill" =>
          os.proc(
            "mill",
            "mill.scalalib.scalafmt.ScalafmtModule/reformatAll"
          ).call(cwd = repoRoot, check = false)
            .exitCode

      if fmtExit != 0 then
        println("\n[ERROR] Scalafmt failed to run!")
        sys.exit(1)

    if hasScalafix then
      println("Fixing code (Scalafix)...")
      val lintExit = buildTool match
        case "sbt" =>
          os.proc("sbt", "scalafixAll").call(cwd = repoRoot, check = false).exitCode
        // Mill has no working built-in/contrib Scalafix module to delegate to
        // (`mill.scalalib.contrib.ScalafixModule` doesn't exist on Maven
        // Central), so the "mill" case runs the same scalafix-cli-via-scala-cli
        // path as the "scala-cli" case. That's fine for this repo's rules
        // (OrganizeImports, DisableSyntax, etc.), which are all syntactic and
        // don't need a semanticdb build.
        case "scala-cli" | "mill" =>
          // `scala-cli --power fix` must be given `.` (not explicit dirs) so it
          // picks up root project.scala's dependencies, incl. test.dep. That
          // also makes it walk excluded scripts (Setup.scala, scripts/, ...)
          // that were never compiled, so filter out the resulting noise.
          val excludePrefixes =
            val projectScalaPath = repoRoot / "project.scala"
            if os.exists(projectScalaPath) then
              val excludeRegex = "//>\\s*using\\s+exclude\\s+(.+)".r
              os.read
                .lines(projectScalaPath)
                .flatMap { line =>
                  excludeRegex
                    .findFirstMatchIn(line.trim)
                    .map(_.group(1).trim.split("\\s+").toList)
                }
                .flatten
                .toList
            else Nil
          val result = os
            .proc("scala-cli", "--power", "fix", ".")
            .call(
              cwd = repoRoot,
              check = false,
              stdout = os.Pipe,
              stderr = os.Pipe,
              mergeErrIntoOut = true
            )
          if result.exitCode == 0 then 0
          else
            val noiseRegex = "^error: SemanticDB not found: (.+)$".r
            def isNoise(line: String): Boolean =
              noiseRegex.findFirstMatchIn(line) match
                case Some(m) =>
                  val path = m.group(1)
                  excludePrefixes.exists(prefix =>
                    path == prefix || path.startsWith(prefix + "/")
                  )
                case None => false
            val meaningfulLines =
              result.out.text().linesIterator.toList.filterNot(isNoise)
            val hasRealError = meaningfulLines.exists(l =>
              l.contains("error:") || l.trim.startsWith("[error]") || l
                .startsWith("--- ")
            )
            if hasRealError then
              meaningfulLines.foreach(println)
              1
            else
              println(
                "✓ Linting clean (ignored known noise from excluded scripts)"
              )
              0

      if lintExit != 0 then
        println("\n[ERROR] Scalafix failed to run!")
        sys.exit(1)

    // Re-stage whichever originally-staged files scalafmt/scalafix rewrote,
    // so the fixes ride along in this commit instead of being left unstaged.
    if stagedFiles.nonEmpty then
      val stillPresent = stagedFiles.filter(f => os.exists(repoRoot / os.RelPath(f)))
      if stillPresent.nonEmpty then
        os.proc("git", "add", "--", stillPresent).call(cwd = repoRoot)

    println("Compiling (scalac)...")
    val compileExit = buildTool match
      case "sbt" =>
        os.proc("sbt", "compile", "Test/compile")
          .call(cwd = repoRoot, check = false)
          .exitCode
      case "mill" =>
        os.proc("mill", "__.compile").call(cwd = repoRoot, check = false).exitCode
      case "scala-cli" =>
        os.proc("scala-cli", "compile", ".")
          .call(cwd = repoRoot, check = false)
          .exitCode

    if compileExit != 0 then
      println("\n[ERROR] Compilation failed! Commit aborted.")
      sys.exit(1)

    println("✓ All pre-commit checks passed successfully!")
