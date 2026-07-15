#!/usr/bin/env scala-cli

//> using scala 3.3.4
//> using dep com.lihaoyi::os-lib:0.11.8

import os._

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

    println("=== Git Pre-Commit Quality Checks ===")

    if hasScalafmt then
      println("Checking code formatting (Scalafmt)...")
      val fmtExit = buildTool match
        case "sbt" =>
          os.proc("sbt", "scalafmtCheckAll")
            .call(cwd = repoRoot, check = false)
            .exitCode
        case "scala-cli" =>
          os.proc("scala-cli", "fmt", "--check", ".")
            .call(cwd = repoRoot, check = false)
            .exitCode
        case "mill" =>
          os.proc(
            "mill",
            "mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll"
          ).call(cwd = repoRoot, check = false)
            .exitCode

      if fmtExit != 0 then
        println("\n[ERROR] Code formatting check failed!")
        println("Please run the formatting tool to fix it:")
        buildTool match
          case "sbt"       => println("  sbt scalafmtAll")
          case "scala-cli" => println("  scala-cli fmt .")
          case "mill" =>
            println("  mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll")
        sys.exit(1)

    if hasScalafix then
      println("Checking code linting (Scalafix)...")
      val lintExit = buildTool match
        case "sbt" =>
          os.proc("sbt", "scalafixAll --check")
            .call(cwd = repoRoot, check = false)
            .exitCode
        case "scala-cli" =>
          val targets = Seq("app/src", "app/test/src").filter(p =>
            os.exists(repoRoot / os.RelPath(p))
          )
          if targets.nonEmpty then
            os.proc("scala-cli", "--power", "fix", "--check" +: targets)
              .call(cwd = repoRoot, check = false)
              .exitCode
          else 0
        case "mill" =>
          os.proc("mill", "app.test")
            .call(cwd = repoRoot, check = false)
            .exitCode

      if lintExit != 0 then
        println("\n[ERROR] Code linting check failed!")
        println("Please run linting to fix it:")
        buildTool match
          case "sbt"       => println("  sbt scalafixAll")
          case "scala-cli" => println("  scala-cli --power fix .")
          case "mill" =>
            println("  mill app.test")
        sys.exit(1)

    println("✓ All pre-commit checks passed successfully!")
