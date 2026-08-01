#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::ujson:4.4.3

import os._

object SetupMdoc:
  def main(args: Array[String]): Unit =
    val repoRoot = args.headOption match
      case Some(path) => os.Path(path, os.pwd)
      case None =>
        try
          os.Path(
            os.proc("git", "rev-parse", "--show-toplevel")
              .call()
              .out
              .text()
              .trim
          )
        catch case _: Exception => os.pwd
    val configPath = repoRoot / ".agents" / "setup_config.json"

    val answers =
      if os.exists(configPath) then
        try ujson.read(os.read(configPath)).obj.map((k, v) => k -> v.str).toMap
        catch case _: Exception => Map.empty[String, String]
      else Map.empty[String, String]

    val docsDir = repoRoot / "docs"
    val hasMdoc = answers.getOrElse("mdoc", "no").toLowerCase.startsWith("y")
    val mdocDocsDir = repoRoot / "mdoc-docs"
    val docsSrcDir = mdocDocsDir / "src" / "main" / "scala"

    if hasMdoc then
      // 1. Create docs/index.md if missing
      os.makeDir.all(docsDir)
      val docIndex = docsDir / "index.md"
      if !os.exists(docIndex) then
        os.write(
          docIndex,
          """# Welcome to the Project Documentation
            |
            |This documentation is compiled and type-checked using **mdoc**.
            |
            |## Scala 3 Code Example
            |
            |```scala mdoc
            |val message = "Hello from Scala 3 type-checked docs!"
            |println(message)
            |```
            |""".stripMargin
        )
        println("✓ Created initial documentation file (docs/index.md)")

      // 2. Create mdoc-docs/src/main/scala/DocsMain.scala
      os.makeDir.all(docsSrcDir)
      val docsMainFile = docsSrcDir / "DocsMain.scala"
      val docsMainContent =
        """//> using scala 3.8.4
          |//> using dep org.scalameta::mdoc:2.9.0
          |
          |package docs
          |
          |import java.nio.file.Paths
          |
          |object DocsMain:
          |  def main(args: Array[String]): Unit =
          |    val settings = mdoc
          |      .MainSettings()
          |      .withIn(Paths.get("docs"))
          |      .withOut(Paths.get("website", "docs"))
          |      .withClasspath(System.getProperty("java.class.path"))
          |      .withArgs(args.toList)
          |    val exitCode = mdoc.Main.process(settings)
          |    if exitCode != 0 then sys.exit(exitCode)
          |""".stripMargin
      os.write.over(docsMainFile, docsMainContent)
      println(
        "✓ Created mdoc documentation runner (mdoc-docs/src/main/scala/DocsMain.scala)"
      )
    else
      // Cleanup if mdoc is disabled
      val docsMainFile = docsSrcDir / "DocsMain.scala"
      if os.exists(docsMainFile) then
        os.remove(docsMainFile)
        println("✓ Removed mdoc documentation runner")
      if os.exists(docsSrcDir) && os.list(docsSrcDir).isEmpty then
        os.remove.all(mdocDocsDir)
        println("✓ Removed mdoc-docs directory")
