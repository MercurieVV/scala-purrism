#!/usr/bin/env scala-cli

//> using scala 3.3.4
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
          // an indented one lives inside the `app` module object.
          if buildContent.linesIterator.exists(_.startsWith("def prePush")) then
            Some("prePush")
          else if buildContent.contains("def prePush") then Some("app.prePush")
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
