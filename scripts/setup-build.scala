#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::ujson:4.4.3

import os._

object SetupBuild:
  var defaultScalaVersion = "3.8.4"

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

    val projectName = repoRoot.last
    val selectedScalaVer =
      answers.getOrElse("scala-version", defaultScalaVersion)
    val buildTool = answers.getOrElse("build-tool", "mill").toLowerCase

    // 1. Resolve dependencies dynamically
    val (resolvedDeps, resolvedTestDeps, resolvedPlugins) =
      getDependenciesAndPlugins(answers, selectedScalaVer)

    // 2. Generate build files based on selected build tool
    val buildFile = repoRoot / "build.sc"
    val buildSbtFile = repoRoot / "build.sbt"
    val projectScalaFile = repoRoot / "project.scala"

    if buildTool == "sbt" then
      updateBuildSbt(
        buildSbtFile,
        projectName,
        selectedScalaVer,
        answers,
        resolvedDeps,
        resolvedTestDeps,
        resolvedPlugins
      )
      if os.exists(buildFile) then os.remove(buildFile)
    else if buildTool == "scala-cli" then
      updateScalaCli(
        projectScalaFile,
        selectedScalaVer,
        resolvedDeps,
        resolvedTestDeps,
        resolvedPlugins
      )
      if os.exists(buildFile) then os.remove(buildFile)
      if os.exists(buildSbtFile) then os.remove(buildSbtFile)
    else
      // mill
      updateBuildSc(
        buildFile,
        projectName,
        selectedScalaVer,
        answers,
        resolvedDeps,
        resolvedTestDeps,
        resolvedPlugins
      )
      updateMillVersion(repoRoot / ".mill-version")
      if os.exists(buildSbtFile) then os.remove(buildSbtFile)

    // 3. Setup Scala CLI config if selected separately for scripting
    val hasScalaCli = answers
      .getOrElse("scripts", "none")
      .toLowerCase == "scala-cli" || buildTool == "scala-cli"
    if hasScalaCli then
      updateScalaCli(
        projectScalaFile,
        selectedScalaVer,
        resolvedDeps,
        resolvedTestDeps,
        resolvedPlugins
      )
    else if buildTool != "scala-cli" && os.exists(projectScalaFile) then
      os.remove(projectScalaFile)

    // 4. Setup/remove Stainless formal verification (standalone CLI, not a
    // compiler plugin: see scripts/stainless-verify.sh for why).
    setupStainless(repoRoot, answers, buildTool, hasScalaCli)

    // 5. Setup/remove the ScalaSemantic MCP configuration.
    setupMcp(repoRoot, answers)

    // 6. Setup resources (logback.xml, application.conf)
    setupResources(repoRoot, answers)

  def fetchLatestStableVersion(
      group: String,
      artifact: String
  ): Option[String] =
    val groupPath = group.replace('.', '/')
    val url =
      s"https://repo1.maven.org/maven2/$groupPath/$artifact/maven-metadata.xml"
    try
      val p = os
        .proc("curl", "-fsSL", "--connect-timeout", "3", url)
        .call(stderr = os.Pipe)
      val xml =
        if p.exitCode == 0 then p.out.text()
        else throw new RuntimeException("Fetch failed")
      val versionRegex = """<version>([^<]+)</version>""".r
      val versions = versionRegex.findAllMatchIn(xml).map(_.group(1)).toList
      val stableVersions = versions.filter(_.matches("^[0-9]+(\\.[0-9]+)*$"))
      if stableVersions.nonEmpty then Some(stableVersions.last)
      else
        val ReleaseRegex = """<release>([^<]+)</release>""".r
        val LatestRegex = """<latest>([^<]+)</latest>""".r
        ReleaseRegex
          .findFirstMatchIn(xml)
          .map(_.group(1))
          .orElse(LatestRegex.findFirstMatchIn(xml).map(_.group(1)))
    catch case _: Exception => None

  def resolveLatestVersion(dep: String, scalaVer: String): String =
    val isScalaDep = dep.contains("::")
    val parts = if isScalaDep then dep.split("::") else dep.split(":")
    if parts.length >= 2 then
      val group = parts(0)
      val rest = parts(1).split(":")
      val artifactName = rest(0).stripPrefix(":")
      val defaultVer = if rest.length > 1 then rest.last else "latest"
      val scalaSuffix =
        if isScalaDep then (if scalaVer.startsWith("3") then "_3" else "_2.13")
        else ""
      val fullArtifact = s"$artifactName$scalaSuffix"

      print(s"Resolving latest version for $group:$fullArtifact... ")
      fetchLatestStableVersion(group, fullArtifact) match
        case Some(latestVer) =>
          println(s"✓ $latestVer")
          val sep = if isScalaDep then "::" else ":"
          s"$group$sep$artifactName:$latestVer"
        case None =>
          println(s"⚠️ Failed. Using default: $defaultVer")
          dep
    else dep

  def getDependenciesAndPlugins(
      answers: Map[String, String],
      scalaVer: String
  ): (List[String], List[String], List[String]) =
    var deps = List.empty[String]
    var testDeps = List.empty[String]
    var plugins = List.empty[String]

    val eco = answers.getOrElse("ecosystem", "typelevel").toLowerCase
    val isZIO = eco == "zio"
    val isTypelevel = eco == "typelevel"

    if isTypelevel then
      deps = deps :+ "org.typelevel::cats-core:2.10.0"
      deps = deps :+ "org.typelevel::cats-effect:3.5.4"
    else if isZIO then
      deps = deps :+ "dev.zio::zio:2.0.21"
      deps = deps :+ "dev.zio::zio-streams:2.0.21"

    val hasWebServer =
      answers.getOrElse("web-server", "no").toLowerCase.startsWith("y")
    if hasWebServer then
      if isTypelevel then
        deps = deps :+ "org.http4s::http4s-ember-server:0.23.27"
        deps = deps :+ "org.http4s::http4s-dsl:0.23.27"
      else if isZIO then deps = deps :+ "dev.zio::zio-http:3.0.0-RC6"

    val hasWebClient =
      answers.getOrElse("web-client", "no").toLowerCase.startsWith("y")
    if hasWebClient then
      if isTypelevel then
        deps = deps :+ "org.http4s::http4s-ember-client:0.23.27"
      else if isZIO then
        deps = deps :+ "com.softwaremill.sttp.client4::zio:4.0.0-RC1"
      else deps = deps :+ "com.softwaremill.sttp.client4::core:4.0.0-RC1"

    val hasDb = answers.getOrElse("db-access", "no").toLowerCase.startsWith("y")
    if hasDb then
      if isTypelevel then
        deps = deps :+ "org.tpolecat::doobie-core:1.0.0-RC5"
        deps = deps :+ "org.tpolecat::doobie-hikari:1.0.0-RC5"
      else if isZIO then deps = deps :+ "io.getquill::quill-jdbc-zio:4.8.4"
      else deps = deps :+ "org.postgresql:postgresql:42.7.3"

    val hasServerless =
      answers.getOrElse("serverless-run", "no").toLowerCase.startsWith("y")
    if hasServerless then
      deps = deps :+ "com.amazonaws:aws-lambda-java-core:1.2.3"
      deps = deps :+ "com.amazonaws:aws-lambda-java-events:3.11.4"

    val testTools =
      answers.getOrElse("test-tools", "munit+shapeless").toLowerCase
    if testTools.contains("munit") then
      testDeps = testDeps :+ "org.scalameta::munit:1.0.0"
    if testTools.contains("shapeless") then
      testDeps = testDeps :+ "org.typelevel::shapeless3-deriving:3.3.0"
    if isZIO || testTools.contains("zio") then
      testDeps = testDeps :+ "dev.zio::zio-test:2.0.21"
      testDeps = testDeps :+ "dev.zio::zio-test-sbt:2.0.21"

    val hasJmh =
      answers.getOrElse("performance-testing", "no").toLowerCase.startsWith("y")
    if hasJmh then deps = deps :+ "org.openjdk.jmh:jmh-core:1.37"

    val hasOptics =
      answers.getOrElse("optics", "no").toLowerCase.startsWith("y")
    if hasOptics then deps = deps :+ "dev.optics::monocle-core:3.2.0"

    val hasDto =
      answers.getOrElse("dto-mapping", "no").toLowerCase.startsWith("y")
    if hasDto then deps = deps :+ "io.scalaland::chimney:0.8.5"

    val hasApiDocs =
      answers.getOrElse("api-docs", "no").toLowerCase.startsWith("y")
    if hasApiDocs then
      deps = deps :+ "com.softwaremill.sttp.tapir::tapir-core:1.10.0"

    val hasLogging =
      answers.getOrElse("logging", "no").toLowerCase.startsWith("y")
    if hasLogging then
      deps = deps :+ "ch.qos.logback:logback-classic:1.5.6"
      if isTypelevel then deps = deps :+ "org.typelevel::log4cats-slf4j:2.6.0"
      else if isZIO then deps = deps :+ "dev.zio::zio-logging-slf4j:2.1.17"
      else deps = deps :+ "org.slf4j:slf4j-api:2.0.13"

    val hasConfig =
      answers.getOrElse("config-loader", "no").toLowerCase.startsWith("y")
    if hasConfig then
      if isZIO then
        deps = deps :+ "dev.zio::zio-config-typesafe:4.0.1"
        deps = deps :+ "dev.zio::zio-config-magnolia:4.0.1"
      else deps = deps :+ "com.github.pureconfig::pureconfig-core:0.17.6"

    val buildTool = answers.getOrElse("build-tool", "mill").toLowerCase
    val finalPlugins =
      if buildTool != "sbt" then plugins :+ "org.wartremover::wartremover:3.2.5"
      else plugins

    val resolvedDeps = deps.map(dep => resolveLatestVersion(dep, scalaVer))
    val resolvedTestDeps =
      testDeps.map(dep => resolveLatestVersion(dep, scalaVer))
    val resolvedPlugins =
      finalPlugins.map(plugin => resolveLatestVersion(plugin, scalaVer))

    (resolvedDeps, resolvedTestDeps, resolvedPlugins)

  def fetchLatestMillVersion(): String =
    val fallback = "1.1.7"
    try
      val p = os
        .proc(
          "curl",
          "-fsSL",
          "--connect-timeout",
          "3",
          "https://api.github.com/repos/com-lihaoyi/mill/releases/latest"
        )
        .call(stderr = os.Pipe)
      if p.exitCode == 0 then
        """"tag_name":\s*"([^"]+)"""".r
          .findFirstMatchIn(p.out.text())
          .map(_.group(1))
          .getOrElse(fallback)
      else fallback
    catch case _: Exception => fallback

  def updateMillVersion(versionFile: os.Path): Unit =
    print("Resolving latest Mill version... ")
    val latest = fetchLatestMillVersion()
    println(s"✓ $latest")
    os.write.over(versionFile, latest + "\n")

  def setupMcp(repoRoot: os.Path, answers: Map[String, String]): Unit =
    val hasMcp =
      answers.getOrElse("mcp-tools", "no").toLowerCase.startsWith("y")
    val configFile = repoRoot / ".agents" / "mcp_config.json"
    val launcherPath = os.home / ".local" / "bin" / "scalasemantic-mcp"

    if hasMcp then
      os.makeDir.all(repoRoot / ".agents")
      if !os.exists(configFile) then
        val config = ujson.Obj(
          "mcpServers" -> ujson.Obj(
            "scala-semantic" -> ujson.Obj(
              "command" -> launcherPath.toString,
              "args" -> ujson.Arr(repoRoot.toString)
            )
          )
        )
        os.write.over(configFile, config.render(indent = 2) + "\n")
        println(
          "✓ Created ScalaSemantic MCP configuration (.agents/mcp_config.json)"
        )

      if !os.exists(launcherPath) then
        println(
          "ℹ ScalaSemantic MCP launcher not found at " + launcherPath + ". Install it with:\n" +
            "  curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh"
        )
    else if os.exists(configFile) then
      os.remove(configFile)
      println(
        "✓ Removed ScalaSemantic MCP configuration (.agents/mcp_config.json)"
      )

  def setupStainless(
      repoRoot: os.Path,
      answers: Map[String, String],
      buildTool: String,
      hasScalaCli: Boolean
  ): Unit =
    val hasStainless =
      answers.getOrElse("stainless", "no").toLowerCase.startsWith("y")
    val scriptsDir = repoRoot / "scripts"
    val verifyScript = scriptsDir / "stainless-verify.sh"
    val confFile = repoRoot / "stainless.conf"

    if hasStainless then
      os.makeDir.all(scriptsDir)
      val verifyContent =
        """#!/usr/bin/env bash
          |set -euo pipefail
          |
          |# Stainless (https://github.com/epfl-lara/stainless) is invoked here as a
          |# standalone CLI, not as a compiler plugin or via sbt-stainless: both of
          |# those integrations are unreliable across build tools and Scala versions.
          |# Only verify a small, side-effect-free "pure kernel" of functions listed
          |# in stainless.conf -- Stainless's supported subset excludes most of the
          |# Scala/Java standard library.
          |
          |cd "$(git rev-parse --show-toplevel)"
          |
          |if ! command -v stainless >/dev/null 2>&1; then
          |  echo "Stainless CLI not found on PATH."
          |  echo "Install it from https://github.com/epfl-lara/stainless/releases"
          |  echo "and make sure the 'stainless' binary is runnable."
          |  echo "Skipping formal verification for now (not blocking)."
          |  exit 0
          |fi
          |
          |if [ ! -f stainless.conf ]; then
          |  echo "No stainless.conf found; nothing to verify."
          |  exit 0
          |fi
          |
          |FILES=$(grep -v '^[[:space:]]*#' stainless.conf | grep -v '^[[:space:]]*$' || true)
          |
          |if [ -z "$FILES" ]; then
          |  echo "stainless.conf lists no files; nothing to verify."
          |  exit 0
          |fi
          |
          |echo "Running Stainless verification on:"
          |echo "$FILES"
          |# shellcheck disable=SC2086
          |stainless $FILES
          |""".stripMargin
      os.write.over(verifyScript, verifyContent)
      try os.perms.set(verifyScript, "rwxr-xr-x")
      catch case _: Exception => ()
      println(
        "✓ Created Stainless verification script (scripts/stainless-verify.sh)"
      )

      if !os.exists(confFile) then
        os.write(
          confFile,
          "# Stainless-verified files: one path per line, relative to the repo root.\n" +
            "# Keep this list small -- Stainless's supported subset excludes most of\n" +
            "# the Scala/Java stdlib, so only list small, side-effect-free functions\n" +
            "# annotated with require/ensuring/@pure. Run: scripts/stainless-verify.sh\n"
        )
        println("✓ Created Stainless configuration (stainless.conf)")

      // The Stainless standard library isn't published to Maven, so
      // `import stainless.lang._` etc. needs an unmanaged jar on the
      // classpath. Wire it in for scala-cli only, where it's a harmless
      // no-op directive if the jar hasn't been dropped in yet; for mill/sbt
      // this needs manual wiring per project (see stainless.conf comments).
      if hasScalaCli then
        val projectScalaFile = repoRoot / "project.scala"
        if os.exists(projectScalaFile) then
          val jarDirective = "//> using jar lib/stainless-library.jar"
          val content = os.read(projectScalaFile)
          if !content.contains(jarDirective) then
            os.write.over(projectScalaFile, content + jarDirective + "\n")

      if !os.exists(repoRoot / "lib" / "stainless-library.jar") then
        println(
          "ℹ Drop the Stainless library jar (extracted from the Stainless CLI " +
            "release download) at lib/stainless-library.jar so 'import stainless.lang._' " +
            "etc. resolve when compiling verified sources -- it isn't published to Maven."
        )
    else
      if os.exists(verifyScript) then
        os.remove(verifyScript)
        println("✓ Removed Stainless verification script")
      if os.exists(confFile) then
        os.remove(confFile)
        println("✓ Removed Stainless configuration (stainless.conf)")

  def updateBuildSc(
      buildFile: os.Path,
      projectName: String,
      scalaVer: String,
      answers: Map[String, String],
      deps: List[String],
      testDeps: List[String],
      plugins: List[String]
  ): Unit =
    val crossComp =
      answers.getOrElse("cross-version", "no").toLowerCase == "yes"
    val hasScoverage =
      answers.getOrElse("scoverage", "no").toLowerCase.startsWith("y")
    val scoverageImport =
      if hasScoverage then "\nimport mill.contrib.scoverage.ScoverageModule"
      else ""
    val scoverageMixin = if hasScoverage then " with ScoverageModule" else ""
    val scoverageVersionField =
      if hasScoverage then "\n  def scoverageVersion = \"2.1.1\"" else ""
    val scoverageTestMixin =
      if hasScoverage then "ScoverageTests" else "ScalaTests"

    val template =
      if crossComp then
        s"""import mill._, scalalib._$scoverageImport
         |
         |val scala3 = "$scalaVer"
         |val scala213 = "2.13.12"
         |
         |object app extends Cross[AppModule](scala3, scala213)
         |trait AppModule extends CrossScalaModule$scoverageMixin {
         |  def scalacOptions = Seq("-Ysemanticdb", "-P:wartremover:traverser:org.wartremover.warts.Unsafe", "-Wunused:imports", "-Werror")$scoverageVersionField
         |  def ivyDeps = Agg(
         |    // [dependencies-start]
         |    // [dependencies-end]
         |  )
         |
         |  def scalacPluginIvyDeps = Agg(
         |    // [plugins-start]
         |    // [plugins-end]
         |  )
         |
         |  object test extends $scoverageTestMixin {
         |    def testFramework = "munit.Framework"
         |    def ivyDeps = Agg(
         |      // [test-dependencies-start]
         |      // [test-dependencies-end]
         |    )
         |  }
         |}
         |""".stripMargin
      else
        s"""import mill._, scalalib._$scoverageImport
         |
         |object app extends ScalaModule$scoverageMixin {
         |  def scalaVersion = "$scalaVer"
         |  def scalacOptions = Seq("-Ysemanticdb", "-P:wartremover:traverser:org.wartremover.warts.Unsafe", "-Wunused:imports", "-Werror")$scoverageVersionField
         |  def ivyDeps = Agg(
         |    // [dependencies-start]
         |    // [dependencies-end]
         |  )
         |
         |  def scalacPluginIvyDeps = Agg(
         |    // [plugins-start]
         |    // [plugins-end]
         |  )
         |
         |  object test extends $scoverageTestMixin {
         |    def testFramework = "munit.Framework"
         |    def ivyDeps = Agg(
         |      // [test-dependencies-start]
         |      // [test-dependencies-end]
         |    )
         |  }
         |}
         |""".stripMargin

    if !os.exists(buildFile) then
      os.write(buildFile, template)
      val buildSbt = buildFile / os.up / "build.sbt"
      if os.exists(buildSbt) then os.remove(buildSbt)

    var content = os.read(buildFile)

    if !content.contains("-Werror") then
      content = content.replace(
        "Seq(\"-Ysemanticdb\")",
        "Seq(\"-Ysemanticdb\", \"-Werror\")"
      )

    def convertToMillDep(dep: String): String =
      val parts = dep.split("::")
      if parts.length >= 2 then
        val org = parts(0)
        val rest = parts(1).split(":")
        val name = rest(0).stripPrefix(":")
        val ver = rest.last
        s"$org::$name:$ver"
      else
        val parts2 = dep.split(":")
        if parts2.length >= 2 then s"${parts2(0)}:${parts2(1)}:${parts2.last}"
        else dep

    def addDepToContent(section: String, dep: String): Unit =
      val depPart = dep.split("::").head
      if !content.contains(depPart) then
        val startMarker = s"// [$section-start]"
        if content.contains(startMarker) then
          content =
            content.replace(startMarker, s"$startMarker\n    ivy\"$dep\",")
          println(s"✓ Added dependency to build.sc: $dep")

    deps.foreach(dep => addDepToContent("dependencies", dep))
    testDeps.foreach(dep => addDepToContent("test-dependencies", dep))
    plugins.foreach(plugin => addDepToContent("plugins", plugin))

    val hasMdoc = answers.getOrElse("mdoc", "no").toLowerCase.startsWith("y")
    if hasMdoc then
      val millMdocModule =
        """
          |object docs extends ScalaModule {
          |  def scalaVersion = "3.3.4"
          |  def ivyDeps = Agg(ivy"org.scalameta::mdoc:2.9.0")
          |}
          |""".stripMargin
      if !content.contains("object docs extends ScalaModule") then
        content = content + "\n" + millMdocModule

    if !content.contains("def prePush") then
      val prePushTask =
        if crossComp then """
          |def prePush() = T.command {
          |  app(scala3).compile()
          |  app(scala3).test.test()()
          |}
          |""".stripMargin
        else """
          |def prePush() = T.command {
          |  app.compile()
          |  app.test.test()()
          |}
          |""".stripMargin
      content = content + "\n" + prePushTask

    os.write.over(buildFile, content)

  def updateBuildSbt(
      sbtFile: os.Path,
      projectName: String,
      scalaVer: String,
      answers: Map[String, String],
      deps: List[String],
      testDeps: List[String],
      plugins: List[String]
  ): Unit =
    val crossComp =
      answers.getOrElse("cross-version", "no").toLowerCase == "yes"
    val scalaVersionsStr =
      if crossComp then s"""Seq("$scalaVer", "2.13.12")"""
      else s"""Seq("$scalaVer")"""

    val template =
      s"""name := "$projectName"
         |version := "0.1.0-SNAPSHOT"
         |
         |scalaVersion := "$scalaVer"
         |crossScalaVersions := $scalaVersionsStr
         |
         |scalacOptions ++= Seq("-Ysemanticdb", "-Wunused:imports", "-Werror")
         |
         |wartremoverErrors ++= Warts.unsafe
         |
         |libraryDependencies ++= Seq(
         |  // [dependencies-start]
         |  // [dependencies-end]
         |)
         |
         |// [plugins-start]
         |// [plugins-end]
         |
         |// Test settings
         |libraryDependencies ++= Seq(
         |  // [test-dependencies-start]
         |  // [test-dependencies-end]
         |).map(_ % Test)
         |""".stripMargin

    if !os.exists(sbtFile) then
      os.write(sbtFile, template)
      val projDir = sbtFile / os.up / "project"
      os.makeDir.all(projDir)
      os.write.over(projDir / "build.properties", "sbt.version=1.9.8\n")
      val buildSc = sbtFile / os.up / "build.sc"
      if os.exists(buildSc) then os.remove(buildSc)

    var content = os.read(sbtFile)

    if !content.contains("wartremoverErrors") then
      content = content.replace(
        "scalacOptions ++= Seq(\"-Ysemanticdb\")",
        "scalacOptions ++= Seq(\"-Ysemanticdb\")\n\nwartremoverErrors ++= Warts.unsafe"
      )

    if !content.contains("-Werror") then
      content = content.replace(
        "scalacOptions ++= Seq(\"-Ysemanticdb\")",
        "scalacOptions ++= Seq(\"-Ysemanticdb\", \"-Werror\")"
      )

    def addDepToSbt(section: String, dep: String): Unit =
      val depPart = dep.split("::").head
      if !content.contains(depPart) then
        val startMarker = s"// [$section-start]"
        if content.contains(startMarker) then
          val sbtDep = convertToSbtDep(dep)
          content = content.replace(startMarker, s"$startMarker\n  $sbtDep,")
          println(s"✓ Added dependency to build.sbt: $sbtDep")

    deps.foreach(dep => addDepToSbt("dependencies", dep))
    testDeps.foreach(dep => addDepToSbt("test-dependencies", dep))

    val pluginsSbt = sbtFile / os.up / "project" / "plugins.sbt"
    var pluginsContent =
      if os.exists(pluginsSbt) then os.read(pluginsSbt) else ""

    val hasScoverage =
      answers.getOrElse("scoverage", "no").toLowerCase.startsWith("y")
    val requiredPlugins = List(
      "addSbtPlugin(\"ch.epfl.scala\" % \"sbt-scalafix\" % \"0.11.1\")",
      "addSbtPlugin(\"org.scalameta\" % \"sbt-scalafmt\" % \"2.5.2\")",
      "addSbtPlugin(\"org.wartremover\" % \"sbt-wartremover\" % \"3.2.5\")"
    ) ++ (if hasScoverage then
            List(
              "addSbtPlugin(\"org.scoverage\" % \"sbt-scoverage\" % \"2.0.12\")"
            )
          else Nil)

    requiredPlugins.foreach { p =>
      val pPart = p.split("%")(1).trim.replace("\"", "")
      if !pluginsContent.contains(pPart) then
        pluginsContent += s"\n$p"
        println(s"✓ Added plugin to project/plugins.sbt: $pPart")
    }

    if !hasScoverage && pluginsContent.contains("sbt-scoverage") then
      pluginsContent = pluginsContent.linesIterator
        .filterNot(_.contains("sbt-scoverage"))
        .mkString("\n")
      println("✓ Removed plugin from project/plugins.sbt: sbt-scoverage")

    plugins.foreach { plugin =>
      val pluginPart = plugin.split("::").head
      if !pluginsContent.contains(pluginPart) then
        val sbtPlugin = convertToSbtPlugin(plugin)
        pluginsContent += s"\naddSbtPlugin($sbtPlugin)"
        println(s"✓ Added plugin to project/plugins.sbt: $sbtPlugin")
    }

    val parentDir = pluginsSbt / os.up
    if !os.exists(parentDir) then os.makeDir.all(parentDir)
    os.write.over(pluginsSbt, pluginsContent)

    val hasMdoc = answers.getOrElse("mdoc", "no").toLowerCase.startsWith("y")
    if hasMdoc then
      val sbtMdocProject =
        s"""
           |lazy val docs = (project in file("mdoc-docs"))
           |  .disablePlugins(wartremover.WartRemover)
           |  .settings(
           |    name := "$projectName-docs",
           |    publish / skip := true,
           |    scalaVersion := "3.3.4",
           |    Compile / run / fork := true,
           |    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
           |    libraryDependencies += "org.scalameta" %% "mdoc" % "2.9.0"
           |  )
           |""".stripMargin
      if !content.contains("lazy val docs =") then
        content = content + "\n" + sbtMdocProject

    if !content.contains("addCommandAlias(\"prePush\"") then
      content =
        content + "\n\naddCommandAlias(\"prePush\", \"; compile ; test\")"

    os.write.over(sbtFile, content)

  def convertToSbtDep(dep: String): String =
    val parts = dep.split("::")
    if parts.length >= 2 then
      val org = parts(0)
      val rest = parts(1).split(":")
      val name = rest(0).stripPrefix(":")
      val ver = rest.last
      s""""$org" %% "$name" % "$ver""""
    else
      val parts2 = dep.split(":")
      if parts2.length >= 2 then
        s""""${parts2(0)}" % "${parts2(1)}" % "${parts2.last}""""
      else s""""$dep""""

  def convertToSbtPlugin(plugin: String): String =
    val sbtDep = convertToSbtDep(plugin)
    s"compilerPlugin($sbtDep)"

  def updateScalaCli(
      projectFile: os.Path,
      scalaVer: String,
      deps: List[String],
      testDeps: List[String],
      plugins: List[String]
  ): Unit =
    val resourceDir = projectFile / os.up / "app" / "resources"
    val resourceLine =
      if os.exists(resourceDir) then
        List("//> using resourceDirs app/resources")
      else Nil

    var lines = List(
      s"//> using scala $scalaVer",
      "//> using options -Ysemanticdb",
      "//> using options -Wunused:imports",
      "//> using options -P:wartremover:traverser:org.wartremover.warts.Unsafe",
      "// //> using options -Werror",
      "//> using exclude Setup.scala",
      "//> using exclude scripts",
      "//> using exclude mdoc-docs",
      "//> using exclude website"
    ) ++ resourceLine

    deps.foreach { dep =>
      lines = lines :+ s"//> using dep $dep"
    }
    testDeps.foreach { dep =>
      lines = lines :+ s"//> using test.dep $dep"
    }
    plugins.foreach { plugin =>
      lines = lines :+ s"//> using plugin $plugin"
    }

    os.write.over(projectFile, lines.mkString("\n") + "\n")
    println(s"✓ Generated Scala CLI config: project.scala")

  def setupResources(repoRoot: os.Path, answers: Map[String, String]): Unit =
    val hasLogging =
      answers.getOrElse("logging", "no").toLowerCase.startsWith("y")
    val hasConfig =
      answers.getOrElse("config-loader", "no").toLowerCase.startsWith("y")

    val resourcesDir = repoRoot / "app" / "resources"

    if hasLogging || hasConfig then os.makeDir.all(resourcesDir)

    if hasLogging then
      val logbackXml = resourcesDir / "logback.xml"
      if !os.exists(logbackXml) then
        val logbackContent =
          """<configuration>
            |    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
            |        <encoder>
            |            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            |        </encoder>
            |    </appender>
            |
            |    <root level="info">
            |        <appender-ref ref="STDOUT" />
            |    </root>
            |</configuration>
            |""".stripMargin
        os.write.over(logbackXml, logbackContent)
        println("✓ Created Logback configuration (app/resources/logback.xml)")
    else
      val logbackXml = resourcesDir / "logback.xml"
      if os.exists(logbackXml) then
        os.remove(logbackXml)
        println("✓ Removed Logback configuration (app/resources/logback.xml)")

    if hasConfig then
      val appConf = resourcesDir / "application.conf"
      if !os.exists(appConf) then
        val confContent =
          """app {
            |  host = "0.0.0.0"
            |  port = 8080
            |}
            |""".stripMargin
        os.write.over(appConf, confContent)
        println(
          "✓ Created application configuration (app/resources/application.conf)"
        )
    else
      val appConf = resourcesDir / "application.conf"
      if os.exists(appConf) then
        os.remove(appConf)
        println(
          "✓ Removed application configuration (app/resources/application.conf)"
        )
