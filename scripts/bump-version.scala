#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8

import os._

object BumpVersion:
  def main(args: Array[String]): Unit =
    val usage =
      """Create the next vX.Y.Z release tag, which the CI workflow turns into a Sonatype Central release
        |(sbt-dynver derives the published version from the tag).
        |
        |Usage:
        |  scala-cli run scripts/bump-version.scala -- [patch|minor|major]
        |
        |Rules:
        |- Uses the highest existing v* semver tag if present, else FIRST_VERSION (scripts/config.sh).
        |- Tags the latest REMOTE RELEASE_BRANCH commit (origin/master by default) directly — it never
        |  switches branches or touches your working tree, so unrelated WIP on a feature branch does NOT
        |  block a release. Under the PR workflow this puts the tag on the merged commit, never a stale
        |  checkout or feature branch.
        |- Creates the tag AND pushes it to origin, which triggers the release. Pushing is unconditional —
        |  there is no opt-in. (`--push` is still accepted for backwards compatibility, but is now a no-op.)""".stripMargin

    if args.isEmpty then
      println(usage)
      sys.exit(0)

    val bumpType = args.head
    if bumpType == "-h" || bumpType == "--help" then
      println(usage)
      sys.exit(0)

    if !Seq("major", "minor", "patch").contains(bumpType) then
      System.err.println(s"Error: Unknown bump type: $bumpType")
      println(usage)
      sys.exit(1)

    // Check remaining args
    args.tail.foreach {
      case "--push" => // deprecated no-op
      case "-h" | "--help" =>
        println(usage)
        sys.exit(0)
      case other =>
        System.err.println(s"Error: Unknown argument: $other")
        sys.exit(1)
    }

    val result = for
      repoRoot <- getRepoRoot()
      releaseBranch = sys.env
        .get("RELEASE_BRANCH")
        .getOrElse(detectReleaseBranch(repoRoot))
      firstVersion = sys.env
        .get("FIRST_VERSION")
        .getOrElse("0.1.0")
      _ <- fetchReleaseBranch(repoRoot, releaseBranch)
      current = latestVersion(repoRoot)
      next <- current match
        case None       => Right(firstVersion)
        case Some(curr) => nextVersion(curr, bumpType)
      newTag = s"v$next"
      _ <-
        if tagExists(repoRoot, newTag) then Left(s"Tag $newTag already exists.")
        else Right(())
      successMsg <- createAndPushTag(repoRoot, newTag, releaseBranch)
    yield successMsg

    result match
      case Right(msg) =>
        println(msg)
        sys.exit(0)
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)

  private def getRepoRoot(): Either[String, os.Path] =
    try
      val res = os.proc("git", "rev-parse", "--show-toplevel").call()
      if res.exitCode == 0 then Right(os.Path(res.out.text().trim))
      else Left("Failed to determine git repo root.")
    catch
      case ex: Exception =>
        Left(s"Failed to locate git repository: ${ex.getMessage}")

  private def detectReleaseBranch(repoRoot: os.Path): String =
    try
      val raw = os
        .proc(
          "git",
          "symbolic-ref",
          "--quiet",
          "--short",
          "refs/remotes/origin/HEAD"
        )
        .call(cwd = repoRoot)
        .out
        .text()
        .trim
      if raw.startsWith("origin/") then raw.stripPrefix("origin/")
      else if raw.nonEmpty then raw
      else "master"
    catch
      case _: Exception =>
        val hasMaster = os
          .proc(
            "git",
            "show-ref",
            "--verify",
            "--quiet",
            "refs/remotes/origin/master"
          )
          .call(cwd = repoRoot, check = false)
          .exitCode == 0
        if hasMaster then "master" else "main"

  private def fetchReleaseBranch(
      repoRoot: os.Path,
      releaseBranch: String
  ): Either[String, Unit] =
    try
      val result = os
        .proc(
          "git",
          "fetch",
          "-q",
          "--tags",
          "origin",
          s"$releaseBranch:refs/remotes/origin/$releaseBranch"
        )
        .call(cwd = repoRoot, check = false)
      if result.exitCode == 0 then Right(())
      else
        Left(
          s"Failed to fetch origin/$releaseBranch. Exit code: ${result.exitCode}"
        )
    catch
      case ex: Exception =>
        Left(s"Exception while fetching branch: ${ex.getMessage}")

  private def latestVersion(repoRoot: os.Path): Option[String] =
    try
      val tags = os
        .proc("git", "tag", "--list", "v*")
        .call(cwd = repoRoot)
        .out
        .text()
        .linesIterator
        .map(_.trim.stripPrefix("v"))
        .filter(_.matches("""^[0-9]+\.[0-9]+\.[0-9]+$"""))
        .toList
      if tags.isEmpty then None
      else
        Some(tags.maxBy { tag =>
          val parts = tag.split('.').flatMap(_.toIntOption)
          if parts.length == 3 then (parts(0), parts(1), parts(2))
          else (0, 0, 0)
        })
    catch case _: Exception => None

  private def nextVersion(
      current: String,
      bump: String
  ): Either[String, String] =
    val parts = current.split('.').flatMap(_.toIntOption)
    if parts.length < 3 then
      Left(
        s"Version '$current' is not in standard semantic versioning format (X.Y.Z)"
      )
    else
      val Array(major, minor, patch) = parts.take(3)
      bump match
        case "patch" => Right(s"$major.$minor.${patch + 1}")
        case "minor" => Right(s"$major.${minor + 1}.0")
        case "major" => Right(s"${major + 1}.0.0")
        case other   => Left(s"Unknown bump type: $other")

  private def tagExists(repoRoot: os.Path, tag: String): Boolean =
    try
      os.proc("git", "rev-parse", "-q", "--verify", s"refs/tags/$tag")
        .call(cwd = repoRoot, check = false, stderr = os.Pipe)
        .exitCode == 0
    catch case _: Exception => false

  private def createAndPushTag(
      repoRoot: os.Path,
      tag: String,
      releaseBranch: String
  ): Either[String, String] =
    try
      val tagRes = os
        .proc(
          "git",
          "tag",
          "-a",
          tag,
          "-m",
          s"Release $tag",
          s"origin/$releaseBranch"
        )
        .call(cwd = repoRoot, check = false)
      if tagRes.exitCode != 0 then
        Left(
          s"Failed to create tag $tag on origin/$releaseBranch. Exit code: ${tagRes.exitCode}"
        )
      else
        val shaRes = os
          .proc("git", "rev-parse", "--short", s"origin/$releaseBranch")
          .call(cwd = repoRoot, check = false)
        val shortSha =
          if shaRes.exitCode == 0 then shaRes.out.text().trim else "unknown SHA"

        val pushRes = os
          .proc("git", "push", "origin", tag)
          .call(cwd = repoRoot, check = false)
        if pushRes.exitCode != 0 then
          Left(
            s"Created tag $tag locally, but failed to push to origin. Exit code: ${pushRes.exitCode}"
          )
        else
          Right(
            s"Created tag $tag on $releaseBranch ($shortSha) and pushed to origin."
          )
    catch
      case ex: Exception =>
        Left(s"Exception during tagging or pushing: ${ex.getMessage}")
