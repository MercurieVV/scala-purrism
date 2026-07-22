#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::ujson:4.4.3

import os._

object SetupGitHooks:
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

    val scriptsDir = repoRoot / "scripts"
    os.makeDir.all(scriptsDir)

    // 1. Write worktree scripts (always enabled)
    setupWorktreeScripts(scriptsDir)

    // 2. Remove legacy LLM interaction hook; arrowstep owns Scala<>LLM protocol state.
    removeLegacyInteractionHook(scriptsDir)

    // 3. Setup/Remove Version Bump script
    setupVersionBump(scriptsDir, answers)

    // 3b. Setup Dependency Update script
    setupDependencyUpdate(scriptsDir)

    // 4. Setup Git Hooks (pre-commit and pre-push)
    setupHooks(repoRoot, scriptsDir, answers)

  def setupWorktreeScripts(scriptsDir: os.Path): Unit =
    val oldStart = scriptsDir / "worktree-start.sh"
    if os.exists(oldStart) then os.remove(oldStart)
    val oldFinish = scriptsDir / "worktree-finish.sh"
    if os.exists(oldFinish) then os.remove(oldFinish)

    val wtStartScript = scriptsDir / "worktree-start.scala"
    val wtStartContent =
      """#!/usr/bin/env scala-cli
        |
        |//> using scala 3.8.4
        |//> using dep com.lihaoyi::os-lib:0.11.8
        |
        |import os._
        |
        |object WorktreeStart:
        |  def main(args: Array[String]): Unit =
        |    if args.isEmpty then
        |      println("Error: task branch name required")
        |      sys.exit(1)
        |
        |    val branch = args.head
        |    val repoRoot = os.Path(os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim)
        |    
        |    // Detect default branch
        |    val base = try {
        |      val raw = os.proc("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD").call(cwd = repoRoot).out.text().trim
        |      raw.stripPrefix("origin/")
        |    } catch {
        |      case _: Exception => "main"
        |    }
        |
        |    val finalBase = if os.proc("git", "show-ref", "--verify", "--quiet", s"refs/heads/$base").call(cwd = repoRoot, check = false).exitCode == 0 then
        |      base
        |    else if os.proc("git", "show-ref", "--verify", "--quiet", "refs/heads/master").call(cwd = repoRoot, check = false).exitCode == 0 then
        |      "master"
        |    else
        |      "main"
        |
        |    val wt = repoRoot / ".worktrees" / branch
        |
        |    if os.exists(wt) then
        |      println(s"Error: Worktree directory already exists: $wt")
        |      sys.exit(1)
        |
        |    println("Fetching latest changes from origin...")
        |    try {
        |      os.proc("git", "fetch", "origin", finalBase, "--quiet").call(cwd = repoRoot)
        |    } catch {
        |      case _: Exception => // ignore fetch failures
        |    }
        |
        |    println(s"Creating branch '$branch' off '$finalBase'...")
        |    val branchCreated = os.proc("git", "branch", branch, s"origin/$finalBase").call(cwd = repoRoot, check = false).exitCode == 0 ||
        |                        os.proc("git", "branch", branch, finalBase).call(cwd = repoRoot, check = false).exitCode == 0
        |
        |    println(s"Adding git worktree at '$wt'...")
        |    val wtAdded = os.proc("git", "worktree", "add", "-b", branch, wt.toString, finalBase).call(cwd = repoRoot, check = false).exitCode == 0 ||
        |                  os.proc("git", "worktree", "add", wt.toString, branch).call(cwd = repoRoot, check = false).exitCode == 0
        |
        |    if !wtAdded then
        |      println("Error: Failed to add git worktree")
        |      sys.exit(1)
        |
        |    // Copy untracked config/rules files
        |    println("Syncing workspace rules and config files to worktree...")
        |    os.makeDir.all(wt / ".agents")
        |    val filesToCopy = Seq(
        |      Path(".agents/mcp_config.json", repoRoot),
        |      Path(".agents/AGENTS.md", repoRoot),
        |      Path(".cursorrules", repoRoot),
        |      Path("scala-rules.md", repoRoot)
        |    )
        |
        |    for f <- filesToCopy if os.exists(f) do
        |      val relative = f.relativeTo(repoRoot)
        |      val dest = wt / relative
        |      os.makeDir.all(dest / os.up)
        |      os.copy.over(f, dest)
        |
        |    println("========================================================================")
        |    println("Worktree created successfully!")
        |    println(s"  Path: $wt")
        |    println(s"  Branch: $branch")
        |    println("")
        |    println("To switch to your new worktree, run:")
        |    println(s"  cd $wt")
        |    println("========================================================================")
        |""".stripMargin
    os.write.over(wtStartScript, wtStartContent)
    try { os.perms.set(wtStartScript, "rwxr-xr-x") }
    catch { case _: Exception => }
    println("✓ Created worktree start script (scripts/worktree-start.scala)")

    val wtFinishScript = scriptsDir / "worktree-finish.scala"
    val wtFinishContent =
      """#!/usr/bin/env scala-cli
        |
        |//> using scala 3.8.4
        |//> using dep com.lihaoyi::os-lib:0.11.8
        |
        |import os._
        |
        |object WorktreeFinish:
        |  def main(args: Array[String]): Unit =
        |    val currentDir = os.pwd
        |    val repoRoot = try {
        |      os.Path(os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim)
        |    } catch {
        |      case _: Exception =>
        |        println("Error: Not inside a git repository")
        |        sys.exit(1)
        |    }
        |
        |    var branch = ""
        |    var wtDir: os.Path = os.Path("/")
        |    var mainRepo: os.Path = os.Path("/")
        |    val wtPattern = "\\\\.worktrees/([^/]+)".r
        |
        |    val currentDirStr = currentDir.toString
        |    wtPattern.findFirstMatchIn(currentDirStr) match
        |      case Some(m) =>
        |        branch = m.group(1)
        |        wtDir = repoRoot
        |        val idx = currentDirStr.indexOf("/.worktrees/")
        |        mainRepo = os.Path(currentDirStr.substring(0, idx))
        |      case None =>
        |        if args.isEmpty then
        |          println("Error: Not in a worktree. Please provide the branch name as an argument.")
        |          sys.exit(1)
        |        branch = args.head
        |        mainRepo = repoRoot
        |        wtDir = mainRepo / ".worktrees" / branch
        |
        |    if !os.exists(wtDir) then
        |      println(s"Error: Worktree directory not found at $wtDir")
        |      sys.exit(1)
        |
        |    val message = if args.length > 1 then args(1) else s"$branch: automated implementation"
        |
        |    // Check for changes in worktree
        |    val isClean = os.proc("git", "status", "--porcelain").call(cwd = wtDir).out.text().trim.isEmpty
        |    if !isClean then
        |      println("Committing changes in worktree...")
        |      os.proc("git", "add", ".").call(cwd = wtDir)
        |      os.proc("git", "commit", "-m", message).call(cwd = wtDir)
        |
        |    // Detect default branch
        |    val base = try {
        |      val raw = os.proc("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD").call(cwd = mainRepo).out.text().trim
        |      raw.stripPrefix("origin/")
        |    } catch {
        |      case _: Exception => "main"
        |    }
        |
        |    val finalBase = if os.proc("git", "show-ref", "--verify", "--quiet", s"refs/heads/$base").call(cwd = mainRepo, check = false).exitCode == 0 then
        |      base
        |    else if os.proc("git", "show-ref", "--verify", "--quiet", "refs/heads/master").call(cwd = mainRepo, check = false).exitCode == 0 then
        |      "master"
        |    else
        |      "main"
        |
        |    println(s"Merging branch '$branch' into '$finalBase'...")
        |    os.proc("git", "checkout", finalBase).call(cwd = mainRepo)
        |    os.proc("git", "merge", branch, "--no-ff", "-m", s"Merge branch '$branch' into $finalBase").call(cwd = mainRepo)
        |
        |    println(s"Removing worktree at '$wtDir'...")
        |    try {
        |      os.proc("git", "worktree", "remove", "--force", wtDir.toString).call(cwd = mainRepo)
        |    } catch {
        |      case _: Exception => // ignore if already gone
        |    }
        |
        |    println(s"Deleting branch '$branch'...")
        |    val branchDeleted = os.proc("git", "branch", "-d", branch).call(cwd = mainRepo, check = false).exitCode == 0 ||
        |                        os.proc("git", "branch", "-D", branch).call(cwd = mainRepo, check = false).exitCode == 0
        |
        |    println("========================================================================")
        |    println("Worktree cleaned up successfully!")
        |    println(s"  Merged branch: $branch into $finalBase")
        |    println(s"  Returned to: $mainRepo (branch: $finalBase)")
        |    println("")
        |    println("To return your shell to the main repository, run:")
        |    println(s"  cd $mainRepo")
        |    println("========================================================================")
        |""".stripMargin
    os.write.over(wtFinishScript, wtFinishContent)
    try { os.perms.set(wtFinishScript, "rwxr-xr-x") }
    catch { case _: Exception => }
    println("✓ Created worktree finish script (scripts/worktree-finish.scala)")

  def removeLegacyInteractionHook(scriptsDir: os.Path): Unit =
    val oldLogScript = scriptsDir / "log-scala-interaction.py"
    if os.exists(oldLogScript) then os.remove(oldLogScript)

    val logScript = scriptsDir / "log-scala-interaction.scala"
    if os.exists(logScript) then
      os.remove(logScript)
      println("✓ Removed legacy LLM interaction logging script")

  def setupVersionBump(
      scriptsDir: os.Path,
      answers: Map[String, String]
  ): Unit =
    val hasVersionBump =
      answers.getOrElse("version-bump", "no").toLowerCase.startsWith("y")
    val bumpScript = scriptsDir / "version-bump.scala"
    if hasVersionBump then
      val bumpContent =
        """#!/usr/bin/env scala-cli
          |
          |//> using scala 3.8.4
          |//> using dep com.lihaoyi::os-lib:0.11.8
          |
          |import os._
          |
          |object VersionBump:
          |  def main(args: Array[String]): Unit =
          |    if args.isEmpty then
          |      println("Error: bump type required (major, minor, patch)")
          |      sys.exit(1)
          |
          |    val bumpType = args.head.toLowerCase
          |    if !Seq("major", "minor", "patch").contains(bumpType) then
          |      println("Error: invalid bump type. Must be: major, minor, patch")
          |      sys.exit(1)
          |
          |    val repoRoot = os.Path(os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim)
          |    val buildSbt = repoRoot / "build.sbt"
          |    val buildSc = repoRoot / "build.sc"
          |    val projectScala = repoRoot / "project.scala"
          |
          |    var currentVersionOpt: Option[String] = None
          |    var targetFileOpt: Option[os.Path] = None
          |    var content = ""
          |
          |    if os.exists(buildSbt) then
          |      targetFileOpt = Some(buildSbt)
          |      content = os.read(buildSbt)
          |      val regex = "(?i)version\\s*:=\\s*\"(.*?)\"".r
          |      regex.findFirstMatchIn(content).foreach { m =>
          |        currentVersionOpt = Some(m.group(1))
          |      }
          |    else if os.exists(buildSc) then
          |      targetFileOpt = Some(buildSc)
          |      content = os.read(buildSc)
          |      val regex = "(?i)def\\s*publishVersion\\s*=\\s*\"(.*?)\"".r
          |      val regex2 = "(?i)val\\s*version\\s*=\\s*\"(.*?)\"".r
          |      regex.findFirstMatchIn(content).orElse(regex2.findFirstMatchIn(content)).foreach { m =>
          |        currentVersionOpt = Some(m.group(1))
          |      }
          |    else if os.exists(projectScala) then
          |      targetFileOpt = Some(projectScala)
          |      content = os.read(projectScala)
          |      val regex = "(?i)//\\s*version\\s*:=\\s*\"(.*?)\"".r
          |      regex.findFirstMatchIn(content).foreach { m =>
          |        currentVersionOpt = Some(m.group(1))
          |      }
          |
          |    val (currentVersion, targetFile) = (currentVersionOpt, targetFileOpt) match
          |      case (Some(v), Some(f)) => (v, f)
          |      case _ =>
          |        val defaultVer = "0.1.0"
          |        if os.exists(projectScala) then
          |          val updated = s"// version := \"$defaultVer\"\n" + os.read(projectScala)
          |          os.write.over(projectScala, updated)
          |          content = updated
          |          (defaultVer, projectScala)
          |        else if os.exists(buildSbt) then
          |          val updated = s"version := \"$defaultVer\"\n" + os.read(buildSbt)
          |          os.write.over(buildSbt, updated)
          |          content = updated
          |          (defaultVer, buildSbt)
          |        else
          |          println("Error: Could not locate build.sbt, build.sc, or project.scala to find version")
          |          sys.exit(1)
          |
          |    println(s"Current version: $currentVersion")
          |
          |    val parts = currentVersion.split('.').flatMap(_.toIntOption)
          |    if parts.length < 3 then
          |      println(s"Error: Version '$currentVersion' is not in standard semantic versioning format (X.Y.Z)")
          |      sys.exit(1)
          |
          |    val Array(major, minor, patch) = parts.take(3)
          |    val nextVersion = bumpType match
          |      case "major" => s"${major + 1}.0.0"
          |      case "minor" => s"$major.${minor + 1}.0"
          |      case "patch" => s"$major.$minor.${patch + 1}"
          |
          |    println(s"Bumping version to: $nextVersion")
          |
          |    val updatedContent = targetFile match
          |      case f if f == buildSbt =>
          |        content.replaceFirst("version\\s*:=\\s*\".*?\"", s"version := \"$nextVersion\"")
          |      case f if f == buildSc =>
          |        if content.contains("def publishVersion") then
          |          content.replaceFirst("def\\s*publishVersion\\s*=\\s*\".*?\"", s"def publishVersion = \"$nextVersion\"")
          |        else
          |          content.replaceFirst("val\\s*version\\s*=\\s*\".*?\"", s"val version = \"$nextVersion\"")
          |      case f if f == projectScala =>
          |        content.replaceFirst("//\\s*version\\s*:=\\s*\".*?\"", s"// version := \"$nextVersion\"")
          |      case _ => content
          |
          |    os.write.over(targetFile, updatedContent)
          |    println(s"✓ Updated version in ${targetFile.relativeTo(repoRoot)}")
          |""".stripMargin
      os.write.over(bumpScript, bumpContent)
      try { os.perms.set(bumpScript, "rwxr-xr-x") }
      catch { case _: Exception => }
      println(
        "✓ Created version bumping utility script (scripts/version-bump.scala)"
      )
    else if os.exists(bumpScript) then
      os.remove(bumpScript)
      println("✓ Removed version bumping utility script")

  def setupDependencyUpdate(
      scriptsDir: os.Path
  ): Unit =
    val updateScript = scriptsDir / "dependency-update.scala"

    // Check if we are running in the template repository directly or can fetch it
    val localScript = os.pwd / "scripts" / "dependency-update.scala"
    val content =
      if os.exists(localScript) then os.read(localScript)
      else
        // Fetch from remote repo
        try
          val url =
            "https://raw.githubusercontent.com/MercurieVV/scala-llm-template/master/scripts/dependency-update.scala"
          val p = os
            .proc("curl", "-fsSL", "--connect-timeout", "3", url)
            .call(stderr = os.Pipe)
          if p.exitCode == 0 then p.out.text()
          else throw new RuntimeException("Remote fetch failed")
        catch case _: Exception => ""

    if content.nonEmpty then
      os.write.over(updateScript, content)
      try { os.perms.set(updateScript, "rwxr-xr-x") }
      catch { case _: Exception => }
      println(
        "✓ Created local dependency update utility script (scripts/dependency-update.scala)"
      )
    else
      println(
        "⚠️ Warning: Could not create scripts/dependency-update.scala (failed to resolve source content)"
      )

  def setupHooks(
      repoRoot: os.Path,
      scriptsDir: os.Path,
      answers: Map[String, String]
  ): Unit =
    val hasGitHooks =
      answers.getOrElse("git-hooks", "no").toLowerCase.startsWith("y")
    val preCommitScript = scriptsDir / "git-pre-commit.scala"
    val prePushScript = scriptsDir / "git-pre-push.scala"

    val gitHooksDir = repoRoot / ".git" / "hooks"
    val gitPreCommitHook = gitHooksDir / "pre-commit"
    val gitPrePushHook = gitHooksDir / "pre-push"

    if hasGitHooks then
      // 1. Write scripts/git-pre-commit.scala
      val preCommitContent =
        """#!/usr/bin/env scala-cli
          |
          |//> using scala 3.8.4
          |//> using dep com.lihaoyi::os-lib:0.11.8
          |
          |import os._
          |
          |object GitPreCommit:
          |  def main(args: Array[String]): Unit =
          |    val repoRoot = os.Path(os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim)
          |
          |    val buildTool = if os.exists(repoRoot / "build.sbt") then "sbt"
          |                    else if os.exists(repoRoot / "build.sc") || os.exists(repoRoot / "build.mill") then "mill"
          |                    else "scala-cli"
          |
          |    val hasScalafmt = os.exists(repoRoot / ".scalafmt.conf")
          |    val hasScalafix = os.exists(repoRoot / ".scalafix.conf")
          |
          |    if !hasScalafmt && !hasScalafix then
          |      println("No formatting or linting configuration found. Pre-commit check skipped.")
          |      sys.exit(0)
          |
          |    println("=== Git Pre-Commit Quality Checks ===")
          |
          |    if hasScalafmt then
          |      println("Checking code formatting (Scalafmt)...")
          |      val fmtExit = buildTool match
          |        case "sbt" =>
          |          os.proc("sbt", "scalafmtCheckAll").call(cwd = repoRoot, check = false).exitCode
          |        case "scala-cli" =>
          |          os.proc("scala-cli", "fmt", "--check", ".").call(cwd = repoRoot, check = false).exitCode
          |        case "mill" =>
          |          os.proc("mill", "mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll").call(cwd = repoRoot, check = false).exitCode
          |
          |      if fmtExit != 0 then
          |        println("\n[ERROR] Code formatting check failed!")
          |        println("Please run the formatting tool to fix it:")
          |        buildTool match
          |          case "sbt" => println("  sbt scalafmtAll")
          |          case "scala-cli" => println("  scala-cli fmt .")
          |          case "mill" => println("  mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll")
          |        sys.exit(1)
          |
          |    if hasScalafix then
          |      println("Checking code linting (Scalafix)...")
          |      val lintExit = buildTool match
          |        case "sbt" =>
          |          os.proc("sbt", "scalafixAll --check").call(cwd = repoRoot, check = false).exitCode
          |        case "scala-cli" =>
          |          val targets = Seq("app/src", "app/test/src").filter(p => os.exists(repoRoot / os.RelPath(p)))
          |          if targets.nonEmpty then
          |            // `scala-cli --power fix` must be given `.` (not explicit dirs) so it
          |            // picks up root project.scala's dependencies, incl. test.dep. That
          |            // also makes it walk excluded scripts (Setup.scala, scripts/, ...)
          |            // that were never compiled, so filter out the resulting noise.
          |            val excludePrefixes =
          |              val projectScalaPath = repoRoot / "project.scala"
          |              if os.exists(projectScalaPath) then
          |                val excludeRegex = "//>\\s*using\\s+exclude\\s+(.+)".r
          |                os.read.lines(projectScalaPath).flatMap { line =>
          |                  excludeRegex.findFirstMatchIn(line.trim).map(_.group(1).trim)
          |                }.toList
          |              else Nil
          |            val result = os.proc("scala-cli", "--power", "fix", "--check", ".")
          |              .call(cwd = repoRoot, check = false, stdout = os.Pipe, stderr = os.Pipe, mergeErrIntoOut = true)
          |            if result.exitCode == 0 then 0
          |            else
          |              val noiseRegex = "^error: SemanticDB not found: (.+)$".r
          |              def isNoise(line: String): Boolean =
          |                noiseRegex.findFirstMatchIn(line) match
          |                  case Some(m) =>
          |                    val path = m.group(1)
          |                    excludePrefixes.exists(prefix => path == prefix || path.startsWith(prefix + "/"))
          |                  case None => false
          |              val meaningfulLines = result.out.text().linesIterator.toList.filterNot(isNoise)
          |              val hasRealError = meaningfulLines.exists(l =>
          |                l.contains("error:") || l.trim.startsWith("[error]") || l.startsWith("--- ")
          |              )
          |              if hasRealError then
          |                meaningfulLines.foreach(println)
          |                1
          |              else
          |                println("✓ Linting clean (ignored known noise from excluded scripts)")
          |                0
          |          else
          |            0
          |        case "mill" =>
          |          val res = os.proc("mill", "mill.scalalib.contrib.ScalafixModule/fix", "--check")
          |            .call(cwd = repoRoot, check = false, stdout = os.Pipe, stderr = os.Pipe)
          |          if res.exitCode != 0 then
          |            val errText = res.err.text()
          |            val outText = res.out.text()
          |            val cleanErr = errText.replaceAll("\u001b\\[[;\\d]*m", "")
          |            val cleanOut = outText.replaceAll("\u001b\\[[;\\d]*m", "")
          |            if cleanErr.contains("Cannot resolve external module") || cleanOut.contains("Cannot resolve external module") then
          |              println("✓ Scalafix is not configured in Mill. Skipping Scalafix check.")
          |              0
          |            else
          |              System.err.print(errText)
          |              System.out.print(outText)
          |              res.exitCode
          |          else 0
          |
          |      if lintExit != 0 then
          |        println("\n[ERROR] Code linting check failed!")
          |        println("Please run linting to fix it:")
          |        buildTool match
          |          case "sbt" => println("  sbt scalafixAll")
          |          case "scala-cli" => println("  scala-cli --power fix .")
          |          case "mill" => println("  mill mill.scalalib.contrib.ScalafixModule/fix")
          |        sys.exit(1)
          |
          |    println("✓ All pre-commit checks passed successfully!")
          |""".stripMargin
      os.write.over(preCommitScript, preCommitContent)
      try { os.perms.set(preCommitScript, "rwxr-xr-x") }
      catch { case _: Exception => }
      println("✓ Created pre-commit script (scripts/git-pre-commit.scala)")

      // 2. Write scripts/git-pre-push.scala
      val prePushContent =
        """#!/usr/bin/env scala-cli
          |
          |//> using scala 3.8.4
          |//> using dep com.lihaoyi::os-lib:0.11.8
          |
          |import os._
          |
          |object GitPrePush:
          |  def main(args: Array[String]): Unit =
          |    val repoRoot = os.Path(os.proc("git", "rev-parse", "--show-toplevel").call().out.text().trim)
          |
          |    val buildTool = if os.exists(repoRoot / "build.sbt") then "sbt"
          |                    else if os.exists(repoRoot / "build.sc") || os.exists(repoRoot / "build.mill") then "mill"
          |                    else "scala-cli"
          |
          |    println("=== Git Pre-Push Verification Checks ===")
          |    println(s"Running compilation and tests using $buildTool...")
          |
          |    val exitCode = buildTool match
          |      case "sbt" =>
          |        val hasPrePushAlias = os.read(repoRoot / "build.sbt").contains("addCommandAlias(\"prePush\"")
          |        val cmd = if hasPrePushAlias then Seq("sbt", "prePush") else Seq("sbt", "compile", "test")
          |        os.proc(cmd).call(cwd = repoRoot, check = false).exitCode
          |
          |      case "mill" =>
          |        val buildFile = if os.exists(repoRoot / "build.mill") then repoRoot / "build.mill" else repoRoot / "build.sc"
          |        val buildContent = os.read(buildFile)
          |        val cmd = if buildContent.contains("def prePush") then Seq("mill", "prePush")
          |                  else if buildContent.contains("object scalafix") then Seq("mill", "scalafix.test")
          |                  else Seq("mill", "app.test")
          |        os.proc(cmd).call(cwd = repoRoot, check = false).exitCode
          |
          |      case "scala-cli" =>
          |        os.proc("scala-cli", "test", ".").call(cwd = repoRoot, check = false).exitCode
          |
          |    if exitCode != 0 then
          |      println("\n[ERROR] Pre-push verification failed! Push aborted.")
          |      sys.exit(1)
          |
          |    val stainlessScript = repoRoot / "scripts" / "stainless-verify.sh"
          |    if os.exists(stainlessScript) then
          |      println("Running Stainless formal verification...")
          |      val stainlessExit = os.proc("bash", stainlessScript.toString).call(cwd = repoRoot, check = false).exitCode
          |      if stainlessExit != 0 then
          |        println("\n[ERROR] Stainless verification failed! Push aborted.")
          |        sys.exit(1)
          |
          |    println("✓ All pre-push checks passed successfully!")
          |""".stripMargin
      os.write.over(prePushScript, prePushContent)
      try { os.perms.set(prePushScript, "rwxr-xr-x") }
      catch { case _: Exception => }
      println("✓ Created pre-push script (scripts/git-pre-push.scala)")

      // 3. Write Git delegate hooks in .git/hooks/ if .git exists
      if os.exists(gitHooksDir) then
        val gitPreCommitContent =
          """#!/bin/sh
            |scala-cli run scripts/git-pre-commit.scala -- "$@"
            |""".stripMargin
        os.write.over(gitPreCommitHook, gitPreCommitContent)
        try { os.perms.set(gitPreCommitHook, "rwxr-xr-x") }
        catch { case _: Exception => }

        val gitPrePushContent =
          """#!/bin/sh
            |scala-cli run scripts/git-pre-push.scala -- "$@"
            |""".stripMargin
        os.write.over(gitPrePushHook, gitPrePushContent)
        try { os.perms.set(gitPrePushHook, "rwxr-xr-x") }
        catch { case _: Exception => }
        println("✓ Installed git pre-commit and pre-push hooks to .git/hooks/")
    else
      // Cleanup
      if os.exists(preCommitScript) then os.remove(preCommitScript)
      if os.exists(prePushScript) then os.remove(prePushScript)
      if os.exists(gitPreCommitHook) then os.remove(gitPreCommitHook)
      if os.exists(gitPrePushHook) then os.remove(gitPrePushHook)
      println("✓ Cleaned up git hooks")
