#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8

import os._

object BumpFix:
  def main(args: Array[String]): Unit =
    val repoRoot = os.Path(
      os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim
    )
    val script = repoRoot / "scripts" / "bump-version.scala"
    val result = os
      .proc("scala-cli", "run", script.toString, "--", "patch" +: args)
      .call(check = false, stdout = os.Inherit, stderr = os.Inherit)
    sys.exit(result.exitCode)
