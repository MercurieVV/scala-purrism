#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8

import os._

object GitPrePush:
  def main(args: Array[String]): Unit =
    val repoRoot = os.Path(
      os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim
    )

    // `build.mill` is the Mill 1.x build file; `build.sc` is the legacy 0.11
    // name. Check the modern one first -- a repo can still carry a stale
    // `build.sc` that Mill itself ignores.
    val millBuildFile =
      Seq("build.mill", "build.sc").map(repoRoot / _).find(os.exists)

    val buildTool =
      if os.exists(repoRoot / "build.sbt") then "sbt"
      else if millBuildFile.isDefined then "mill"
      else "scala-cli"

    println("=== Git Pre-Push Verification Checks ===")

    val hasScalafmt = os.exists(repoRoot / ".scalafmt.conf")
    val hasScalafix = os.exists(repoRoot / ".scalafix.conf")

    if buildTool == "mill" && hasScalafmt then
      println("Checking code formatting (Scalafmt)...")
      val fmtExit = os
        .proc("mill", "mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll")
        .call(cwd = repoRoot, check = false)
        .exitCode
      if fmtExit != 0 then
        println("\n[ERROR] Code formatting check failed! Push aborted.")
        println("Run: mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll")
        sys.exit(1)

    if buildTool == "mill" && hasScalafix then
      println("Checking code linting (Scalafix)...")
      // Same scala-cli-via-fallback path as scripts/git-pre-commit.scala --
      // Mill has no working built-in/contrib Scalafix module (see that
      // script's comment for details).
      val excludePrefixes =
        val projectScalaPath = repoRoot / "project.scala"
        if os.exists(projectScalaPath) then
          val excludeRegex = "//>\\s*using\\s+exclude\\s+(.+)".r
          os.read
            .lines(projectScalaPath)
            .flatMap { line =>
              excludeRegex.findFirstMatchIn(line.trim).map(_.group(1).trim)
            }
            .toList
        else Nil
      val result = os
        .proc("scala-cli", "--power", "fix", "--check", ".")
        .call(
          cwd = repoRoot,
          check = false,
          stdout = os.Pipe,
          stderr = os.Pipe,
          mergeErrIntoOut = true
        )
      val lintExit =
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
            println("✓ Linting clean (ignored known noise from excluded scripts)")
            0
      if lintExit != 0 then
        println("\n[ERROR] Code linting check failed! Push aborted.")
        println("Run: scala-cli --power fix .")
        sys.exit(1)

    println(s"Running compilation and tests using $buildTool...")

    val exitCode = buildTool match
      case "sbt" =>
        val hasPrePushAlias = os
          .read(repoRoot / "build.sbt")
          .contains("addCommandAlias(\"prePush\"")
        val cmd =
          if hasPrePushAlias then Seq("sbt", "prePush")
          else Seq("sbt", "compile", "test")
        os.proc(cmd).call(cwd = repoRoot, check = false).exitCode

      case "mill" =>
        val buildContent = os.read(millBuildFile.get)
        val prePushTarget =
          // A `def prePush` at column 0 is a root-level task (`mill prePush`);
          // nested module tasks should be invoked explicitly by a root command.
          if buildContent.linesIterator.exists(_.startsWith("def prePush")) then
            Some("prePush")
          else None
        // Without a prePush task, fall back to every test module in the build
        // rather than assuming an `app` module exists.
        val cmd = Seq("mill", prePushTarget.getOrElse("__.test"))
        os.proc(cmd).call(cwd = repoRoot, check = false).exitCode

      case "scala-cli" =>
        os.proc("scala-cli", "test", ".")
          .call(cwd = repoRoot, check = false)
          .exitCode

    if exitCode != 0 then
      println("\n[ERROR] Pre-push verification failed! Push aborted.")
      sys.exit(1)

    val stainlessScript = repoRoot / "scripts" / "stainless-verify.sh"
    if os.exists(stainlessScript) then
      println("Running Stainless formal verification...")
      val stainlessExit = os
        .proc("bash", stainlessScript.toString)
        .call(cwd = repoRoot, check = false)
        .exitCode
      if stainlessExit != 0 then
        println("\n[ERROR] Stainless verification failed! Push aborted.")
        sys.exit(1)

    println("✓ All pre-push checks passed successfully!")
