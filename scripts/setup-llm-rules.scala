#!/usr/bin/env scala-cli

//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::ujson:4.4.3

import os._

object SetupLlmRules:
  val rulesDir = os.home / ".config" / "llm-rules"
  val masterRulesFile = rulesDir / "scala-rules.md"
  val githubRulesUrl =
    "https://raw.githubusercontent.com/MercurieVV/scala-llm-template/master/scala-rules.md"

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

    val scalaVer = answers.getOrElse("scala-version", "3.8.4")

    // 1. Sync shared rules
    updateMasterRules()

    // 2. Generate LLM rules content
    val llmRulesContent = generateLlmRules(answers, scalaVer)

    // 3. Write files
    os.write.over(repoRoot / "scala-rules.md", llmRulesContent)
    println("✓ Updated scala-rules.md in project root")

    os.write.over(repoRoot / ".cursorrules", llmRulesContent)
    println("✓ Updated .cursorrules in project root")

    val agentsDir = repoRoot / ".agents"
    os.makeDir.all(agentsDir)
    os.write.over(agentsDir / "AGENTS.md", llmRulesContent)
    println("✓ Updated .agents/AGENTS.md")

    // 4. Update guide files
    updateGuideFile(repoRoot / "CLAUDE.md", answers)
    updateGuideFile(repoRoot / "CONVENTIONS.md", answers)

  def updateMasterRules(): Unit =
    if !os.exists(rulesDir) then os.makeDir.all(rulesDir)
    println(s"Synchronizing shared master rules at $masterRulesFile...")
    try
      val content = clippyFetch(githubRulesUrl)
      os.write.over(masterRulesFile, content)
      println("✓ Rules synchronized from GitHub.")
    catch
      case _: Exception =>
        println(
          "⚠️ Could not fetch rules from GitHub. Using/creating local cache."
        )
        if !os.exists(masterRulesFile) then
          val defaultRules =
            """# Scala 3 LLM Guidelines & Coding Rules
              |
              |You are acting as an expert Scala engineer. When writing, refactoring, or reviewing Scala code in this codebase, you must follow these rules strictly:
              |
              |## 1. Syntax & Style (Scala 3)
              |* Use the new Scala 3 optional braces syntax (significant indentation).
              |* Do not write curly braces `{}` for packages, classes, methods, or control flow unless necessary.
              |* Indentation size: 2 spaces.
              |* Avoid using semicolons.
              |
              |## 2. Functional Programming Standards
              |* **Immutability First**: Use `val` for all variables. Do not use `var` unless absolutely required for performance in a local loop.
              |* **Immutable Collections**: Always use standard immutable collections (`List`, `Vector`, `Map`, `Set`).
              |* **No Nulls**: Do not return `null` or use `Option.get`. Always handle optionals safely using pattern matching.
              |* **Error Handling**: Do not throw custom exceptions. Instead, return failures explicitly using `Either` or `Try`.
              |""".stripMargin
          os.write(masterRulesFile, defaultRules)
          println("✓ Initialized fallback master rules locally.")

  def clippyFetch(url: String): String =
    val p = os
      .proc("curl", "-fsSL", "--connect-timeout", "3", url)
      .call(stderr = os.Pipe)
    if p.exitCode == 0 then p.out.text()
    else throw new RuntimeException("Fetch failed")

  def generateLlmRules(answers: Map[String, String], scalaVer: String): String =
    val sb = new java.lang.StringBuilder()
    sb.append("# Scala 3 LLM Guidelines & Coding Rules\n\n")
    sb.append(
      "You are acting as an expert Scala engineer. When writing, refactoring, or reviewing Scala code in this codebase, you must follow these rules strictly:\n\n"
    )

    sb.append("## 1. Syntax & Style\n")
    sb.append(
      "* Syntax and coding styles are controlled automatically by Scalafmt and Scalafix linting rules. Do not manually format code in ways that violate these configurations; rely on automatic formatting and fixing tools.\n\n"
    )

    sb.append("## 2. Functional Programming Standards\n")
    sb.append(
      "* **Pure FP Style**: Always write code in a pure functional programming style.\n"
    )
    sb.append(
      "* **Consequences**: Avoid mutable state (`var`), handle all side effects explicitly, never return or use `null`, and represent errors explicitly using type-safe structures like `Either`, `Try`, or monadic effects.\n\n"
    )

    val crossComp =
      answers.getOrElse("cross-version", "no").toLowerCase == "yes"
    if crossComp then
      sb.append("## 3. Cross-Version Compilation (Scala 2.13 & Scala 3)\n")
      sb.append(
        "* Ensure all code is compatible with both Scala 2.13 and Scala 3.\n"
      )
      sb.append(
        "* Avoid using Scala 3 exclusive features (such as `enum`, `given`/`using` (unless backported or conditionally compiled), export clauses, parameter untupling) that break Scala 2.13 compilation.\n"
      )
      sb.append("* Use cross-compatible styles for syntax where possible.\n\n")
    else
      sb.append("## 3. Scala 3 Features\n")
      sb.append(
        "* Feel free to use advanced Scala 3 features: `given`/`using` for implicits, `enum` for ADTs, extension methods, type lambdas, and union/intersection types.\n\n"
      )

    val eco = answers.getOrElse("ecosystem", "none").toLowerCase
    if eco == "typelevel" then
      sb.append("## 4. Cats & Cats Effect (Typelevel Ecosystem)\n")
      sb.append(
        "* Use Cats Effect for managing side effects and concurrency.\n"
      )
      sb.append(
        "* **Abstraction First**: Prefer programming to abstract typeclasses (e.g., `Monad`, `Sync`, `Concurrent`, `Temporal`, `ApplicativeError`) instead of concrete types/instances (like `IO`) to ensure generic, composable, and easily testable code.\n"
      )
      sb.append(
        "* Avoid running IO unsafely (never call `unsafeRunSync`). Let the runtime execute the IO at the application entry point (`IOApp`).\n"
      )
      sb.append(
        "* Use cats syntax import (`import cats.syntax.all.*`) for map, flatMap, traverse, sequence, etc.\n\n"
      )
    else if eco == "zio" then
      sb.append("## 4. ZIO Ecosystem\n")
      sb.append(
        "* Use `zio.ZIO` to model all side effects. Do not use `scala.concurrent.Future`.\n"
      )
      sb.append(
        "* Prefer `ZIO[R, E, A]` to represent environment `R`, error `E`, and value `A`.\n"
      )
      sb.append(
        "* Manage dependencies and application services using `ZLayer`.\n"
      )
      sb.append(
        "* Handle errors using ZIO's built-in error channels (failures vs. defects).\n"
      )
      sb.append(
        "* Avoid unsafe execution of ZIO effects (never use `Runtime.default.unsafe.run`).\n\n"
      )
    else
      sb.append("## 4. Standard Library Concurrency & IO\n")
      sb.append(
        "* Use standard library concurrency primitives, prefer `scala.concurrent.Future` or pure state transitions.\n"
      )
      sb.append(
        "* If using `Future`, ensure an implicit `ExecutionContext` is provided correctly.\n\n"
      )

    val hasWebServer =
      answers.getOrElse("web-server", "no").toLowerCase.startsWith("y")
    if hasWebServer then
      sb.append("## 5. Web Server\n")
      if eco == "typelevel" then
        sb.append(
          "* Use **Http4s Ember** for defining routes and serving HTTP.\n"
        )
        sb.append(
          "* Use http4s DSL (`import org.http4s.dsl.io.*`) for routing.\n"
        )
        sb.append(
          "* Integrate with Circe for JSON serialization/deserialization.\n\n"
        )
      else if eco == "zio" then
        sb.append("* Use **ZIO-HTTP** for defining routes and serving HTTP.\n")
        sb.append(
          "* Compose routes using ZIO-HTTP's DSL (`Routes` / `Method` pattern).\n\n"
        )
      else
        sb.append(
          "* Use standard web framework library APIs configured in the build tool.\n\n"
        )

    val hasWebClient =
      answers.getOrElse("web-client", "no").toLowerCase.startsWith("y")
    if hasWebClient then
      sb.append("## 6. Web Client\n")
      if eco == "typelevel" then
        sb.append(
          "* Use **Http4s Ember Client** or **STTP** with Http4s backend for outgoing HTTP requests.\n"
        )
        sb.append("* Manage client lifecycle properly using `Resource`.\n\n")
      else if eco == "zio" then
        sb.append(
          "* Use **STTP** with ZIO backend (`SttpBackend[Task, ...]` or similar) or ZIO-HTTP client.\n\n"
        )
      else
        sb.append(
          "* Use **STTP Core** or standard HTTP client for outgoing HTTP requests.\n\n"
        )

    val hasDb = answers.getOrElse("db-access", "no").toLowerCase.startsWith("y")
    if hasDb then
      sb.append("## 7. Database Access\n")
      if eco == "typelevel" then
        sb.append("* Use **Doobie** for type-safe database queries.\n")
        sb.append("* Write SQL queries using the `sql` interpolator.\n")
        sb.append(
          "* Use `transact` to run connection IOs inside a transaction.\n\n"
        )
      else if eco == "zio" then
        sb.append("* Use **Quill** with JDBC ZIO for database access.\n")
        sb.append("* Define queries using Quill's compile-time quotations.\n\n")
      else
        sb.append(
          "* Use **PostgreSQL JDBC** or standard database libraries.\n\n"
        )

    val hasServerless =
      answers.getOrElse("serverless-run", "no").toLowerCase.startsWith("y")
    if hasServerless then
      sb.append("## 8. Serverless Deployment (AWS Lambda)\n")
      sb.append(
        "* Structure handlers using AWS Lambda Java Core (`RequestHandler` or `RequestStreamHandler`).\n"
      )
      sb.append(
        "* Keep initialization logic outside the handler to minimize cold starts.\n\n"
      )

    val testTools = answers.getOrElse("test-tools", "none").toLowerCase
    if testTools.contains("munit") || testTools.contains("shapeless") then
      sb.append("## 9. Testing Guidelines (MUnit)\n")
      sb.append("* Write tests using **MUnit**. Extend `munit.FunSuite`.\n")
      sb.append(
        "* **Preferred Styles**: Prefer Property-Based (PB) testing, Golden (snapshot) testing, and mutation testing via Stryker4s.\n"
      )
      sb.append(
        "* **Formal Verification**: Search for opportunities to apply Stainless formal verification to functional properties and core logic.\n"
      )
      sb.append(
        "* Leverage MUnit assertions like `assertEquals`, `assertNotEquals`, `intercept`.\n\n"
      )
    else if testTools.contains("zio") then
      sb.append("## 9. Testing Guidelines (ZIO Test)\n")
      sb.append("* Write tests using **ZIO Test**. Extend `ZIOSpecDefault`.\n")
      sb.append(
        "* **Preferred Styles**: Prefer Property-Based (PB) testing, Golden (snapshot) testing, and mutation testing via Stryker4s.\n"
      )
      sb.append(
        "* **Formal Verification**: Search for opportunities to apply Stainless formal verification to functional properties and core logic.\n"
      )
      sb.append("* Use assertion macros like `assertZIO`, `assertTrue`.\n\n")
    else
      sb.append("## 9. Testing Guidelines\n")
      sb.append(
        "* **Preferred Styles**: Prefer Property-Based (PB) testing, Golden (snapshot) testing, and mutation testing via Stryker4s.\n"
      )
      sb.append(
        "* **Formal Verification**: Search for opportunities to apply Stainless formal verification to functional properties and core logic.\n\n"
      )

    val hasStainless =
      answers.getOrElse("stainless", "no").toLowerCase.startsWith("y")
    if hasStainless then
      sb.append("## 10. Formal Verification (Stainless)\n")
      sb.append(
        "* Stainless runs as a standalone CLI via `scripts/stainless-verify.sh`, not as a compiler plugin or sbt-stainless -- both are unreliable across build tools and Scala versions.\n"
      )
      sb.append(
        "* List only the files to verify in `stainless.conf` (one path per line). Keep this list small: Stainless's supported subset excludes most of the Scala/Java stdlib.\n"
      )
      sb.append(
        "* Prefer verifying a small, side-effect-free \"pure kernel\" of functions (e.g. leaf arithmetic) rather than whole modules; have collection-heavy callers delegate down to them instead of duplicating logic.\n"
      )
      sb.append(
        "* Annotate verified code with `require`/`ensuring`/`@pure` (and `@ghost`/`@extern` where appropriate). Avoid mutable state or unsupported Scala features in verified sections.\n"
      )
      sb.append(
        "* `import stainless.lang._` etc. needs `lib/stainless-library.jar` on the classpath (extracted from the Stainless CLI release download) since it isn't published to Maven.\n\n"
      )

    val hasStryker =
      answers.getOrElse("stryker", "no").toLowerCase.startsWith("y")
    if hasStryker then
      sb.append("## 11. Mutation Testing (Stryker)\n")
      sb.append(
        "* Write comprehensive tests that verify behavior under mutation.\n"
      )
      sb.append("* Ensure tests are not brittle or order-dependent.\n\n")

    val hasJmh =
      answers.getOrElse("performance-testing", "no").toLowerCase.startsWith("y")
    if hasJmh then
      sb.append("## 12. Performance & JMH Benchmarking\n")
      sb.append("* Use JMH for microbenchmarks.\n")
      sb.append(
        "* Annotate benchmark classes with `@State(Scope.Thread)` and methods with `@Benchmark`.\n"
      )
      sb.append(
        "* Avoid side effects or compiler optimizations (like dead code elimination) from skewing benchmark results (use `Blackhole` if necessary).\n\n"
      )

    sb.append("## 13. Code Quality (Scalafmt, Scalafix, Wartremover)\n")
    sb.append("* Keep code formatted via Scalafmt rules.\n")
    sb.append(
      "* Use Scalafix to organize imports and remove unused imports or syntax warnings automatically.\n"
    )
    sb.append(
      "* **Wartremover**: Pure functional programming safety is checked via Wartremover's Unsafe warts. Ensure your code does not trigger any unsafe warts (such as `Null`, `Var`, `Throw`, `Return`, `IsInstanceOf`, `AsInstanceOf`).\n\n"
    )

    val hasOptics =
      answers.getOrElse("optics", "no").toLowerCase.startsWith("y")
    if hasOptics then
      sb.append("## 14. Immutable Data Optics (Monocle)\n")
      sb.append(
        "* Use Monocle lenses, prisms, and optionals to modify deeply nested immutable structures instead of nested `copy` calls.\n\n"
      )

    val hasDto =
      answers.getOrElse("dto-mapping", "no").toLowerCase.startsWith("y")
    if hasDto then
      sb.append("## 15. Data Transformation (Chimney)\n")
      sb.append(
        "* Use Chimney for type-safe data transformations (`transformInto`) between DTOs, API models, and Domain models.\n\n"
      )

    val hasApiDocs =
      answers.getOrElse("api-docs", "no").toLowerCase.startsWith("y")
    if hasApiDocs then
      sb.append("## 16. API Specifications (Tapir)\n")
      sb.append(
        "* Define endpoints using Tapir for declarative, type-safe API descriptions.\n"
      )
      sb.append("* Generate OpenAPI documentation from Tapir endpoints.\n\n")

    val hasMcp =
      answers.getOrElse("mcp-tools", "no").toLowerCase.startsWith("y")
    if hasMcp then
      sb.append("## 18. ScalaSemantic MCP Rules\n")
      sb.append(
        "* For any Scala (`.scala`) source questions, file operations, search, or analysis, use ScalaSemantic MCP tools before shell text tools.\n"
      )
      sb.append(
        "* Preferably compile code before usage, therefore more ScalaSemantic functions could be used with better result.\n"
      )
      sb.append(
        "* **NEVER** use generic text/file-reading, viewing, or searching tools (like `view_file`, `grep_search`, or shell commands like `rg`/`grep`/`cat`/`sed`) on `.scala` files unless the MCP tools are unavailable or failing.\n"
      )
      sb.append(
        "* **ALWAYS** use the custom tools provided by the `scala-semantic` MCP server:\n"
      )
      sb.append(
        "  * **To read/view the contents of a file**: Use the `annotated_source` MCP tool.\n"
      )
      sb.append(
        "  * **For all other queries** (searching, finding usages, hierarchies, etc.): Select the appropriate tool from the registered `scala-semantic` MCP tools.\n\n"
      )

    sb.append("## 17. Project Maintenance\n")
    sb.append(
      "* **Scala Steward**: Periodically run Scala Steward updates to keep the project's dependencies and compiler plugins up-to-date.\n\n"
    )

    sb.append("## 19. Command Execution & Token Output Minimization\n")
    sb.append(
      "* **Minimize Output Volume**: To prevent token bloat, always minimize stdout/stderr output when running commands. Avoid dumping massive log streams, command traces, or verbose build success messages into the LLM context.\n"
    )
    sb.append(
      "* **Use Token-Optimized CLI Proxies**: If `rtk` (Rust Token Killer) is installed and verified, prefix commands with `rtk` (e.g. `rtk git status`, `rtk sbt test`) to leverage transparent token-filtering proxying.\n"
    )
    sb.append(
      "* **Filter Log Streams & Errors**: When running tests, compiles, or other scripts, redirect or pipe outputs to isolate errors or limit lines:\n"
    )
    sb.append(
      "  * Pipe compile/test logs to `grep` with context flags to capture only errors (e.g., `| grep -C 3 -i error` or `| grep -i fail`).\n"
    )
    sb.append(
      "  * Use `head -n N` or `tail -n N` to capture only a small, representative slice of command outputs when scanning general outputs.\n"
    )
    sb.append(
      "  * Suppress standard success logs or stdout using redirect syntax (`> /dev/null`) if you only need the command exit status or error streams.\n\n"
    )

    sb.append("## 20. Custom Command: /scalaFeature\n")
    sb.append(
      "* **Purpose**: Implement the custom request `/scalaFeature ${feature}` by adding the requested library or capability to the current Scala project.\n"
    )
    sb.append(
      "* **Step 1 (Detect Build Tool & Scala Version)**: Detect if the project uses Scala CLI (`project.scala`), Mill (`build.sc`/`build.mill`), or SBT (`build.sbt`), and identify the Scala version and current dependencies.\n"
    )
    sb.append(
      "* **Step 2 (Map Feature & Propose Bridges/Modules)**: Map the requested feature to dependency coordinates. Propose all relevant modules of the library (e.g. for Circe: `core`, `generic`, `parser`) and bridge libraries for existing dependencies (e.g. if the project uses `cats` and adding `circe`, suggest `circe-cats`; if using `pureconfig`, suggest `pureconfig-circe`).\n"
    )
    sb.append(
      "* **Step 3 (Resolve Latest Version)**: Fetch the latest stable versions from Maven Central (use `rtk curl -fsSL https://repo1.maven.org/maven2/.../maven-metadata.xml` or similar search).\n"
    )
    sb.append(
      "* **Step 4 (Apply Changes)**: Ask for user confirmation, then edit the build files (`project.scala`, `build.sc`/`build.mill`, or `build.sbt`) using correct syntax.\n"
    )
    sb.append(
      "* **Step 5 (Verify)**: Run code formatting and verify compilation to ensure there are no library resolution or compiler conflicts.\n\n"
    )

    sb.toString()

  def updateGuideFile(file: os.Path, answers: Map[String, String]): Unit =
    if os.exists(file) then
      var content = os.read(file)

      val buildTool = answers.getOrElse("build-tool", "mill").toLowerCase
      val hasStryker =
        answers.getOrElse("stryker", "no").toLowerCase.startsWith("y")
      val hasFormatting = true
      val hasLinting = true

      val compileCmd =
        if buildTool == "sbt" then "sbt compile"
        else if buildTool == "scala-cli" then "scala-cli compile ."
        else "mill app.compile"

      val runCmd =
        if buildTool == "sbt" then "sbt run"
        else if buildTool == "scala-cli" then "scala-cli run ."
        else "mill app.run"

      val testCmd =
        if buildTool == "sbt" then "sbt test"
        else if buildTool == "scala-cli" then "scala-cli test ."
        else "mill app.test"

      val strykerRow = if hasStryker then
        val cmd = if buildTool == "sbt" then "sbt stryker" else "stryker4s run"
        s"\n| **Run Mutation Tests** | `$cmd` |"
      else ""

      val formatRow = if hasFormatting then
        val cmd =
          if buildTool == "sbt" then "sbt scalafmtAll"
          else if buildTool == "scala-cli" then "scala-cli fmt ."
          else "mill mill.scalalib.ScalafmtModule/reformat"
        s"\n| **Format Code** | `$cmd` |"
      else ""

      val lintRow = if hasLinting then
        val cmd =
          if buildTool == "sbt" then "sbt scalafixAll"
          else if buildTool == "scala-cli" then "scala-cli --power scalafix ."
          else "mill mill.scalalib.contrib.ScalafixModule/fix"
        s"\n| **Lint Code** | `$cmd` |"
      else ""

      val hasMdoc = answers.getOrElse("mdoc", "no").toLowerCase.startsWith("y")
      val mdocRow = if hasMdoc then
        val cmd =
          if buildTool == "sbt" then "sbt docs/run"
          else if buildTool == "scala-cli" then
            "scala-cli run mdoc-docs/src/main/scala/DocsMain.scala -- ."
          else "mill docs.run"
        s"\n| **Compile Docs (Mdoc)** | `$cmd` |"
      else ""

      val wtStartRow =
        "\n| **Start Git Worktree** | `scala-cli run scripts/worktree-start.scala -- <branch>` |"
      val wtFinishRow =
        "\n| **Finish Git Worktree** | `scala-cli run scripts/worktree-finish.scala` |"

      val newCommandsSection =
        s"""## 1. Key Commands
           |
           |Use the following commands to build, test, and format the project:
           |
           || Operation | Command |
           || :--- | :--- |
           || **Compile Project** | `$compileCmd` |
           || **Run Application** | `$runCmd` |
           || **Run Unit Tests** | `$testCmd` |
           || **Run Scala Steward** | `scala-steward` |
           || **Local Dependency Update** | `scala-cli run scripts/dependency-update.scala -- [target-dir]` |""".stripMargin + strykerRow + formatRow + lintRow + mdocRow + wtStartRow + wtFinishRow

      // Replace from "## 1. Key Commands" to the next "---" (skipping the "---" inside the table header)
      val startIdx = content.indexOf("## 1. Key Commands")
      if startIdx != -1 then
        val endIdx = {
          val idx1 = content.indexOf("\n---", startIdx)
          val idx2 = content.indexOf("\r\n---", startIdx)
          if idx1 != -1 && idx2 != -1 then Math.min(idx1, idx2)
          else if idx1 != -1 then idx1
          else idx2
        }
        if endIdx != -1 then
          content =
            content.substring(0, startIdx) + newCommandsSection + "\n" + content
              .substring(endIdx)

      val newSetupSection =
        s"""## 2. Project Generation & Update Tool
           |
           |The project is generated and updated dynamically using an interactive Scala CLI script.
           |
           |*   **Setup Script:** `Setup.scala` (runs on Scala 3 + `os-lib`).
           |*   **Run command (local script):**
           |    ```bash
           |    scala-cli run /path/to/Setup.scala -- .
           |    ```
           |*   **Run command (internet remote):**
           |    ```bash
           |    scala-cli run https://raw.githubusercontent.com/MercurieVV/scala-llm-template/master/Setup.scala -- .
           |    ```
           |""".stripMargin

      val startSetupIdx = content.indexOf("## 2. Project Generation")
      if startSetupIdx != -1 then
        val endSetupIdx = {
          val idx1 = content.indexOf("\n---", startSetupIdx)
          val idx2 = content.indexOf("\r\n---", startSetupIdx)
          if idx1 != -1 && idx2 != -1 then Math.min(idx1, idx2)
          else if idx1 != -1 then idx1
          else idx2
        }
        if endSetupIdx != -1 then
          content = content.substring(
            0,
            startSetupIdx
          ) + newSetupSection + "\n" + content
            .substring(endSetupIdx)

      val mcpEnabled =
        answers.getOrElse("mcp-tools", "no").toLowerCase.startsWith("y")

      val mcpBullet =
        if mcpEnabled then
          "\n*   **ScalaSemantic MCP configuration:** [.agents/mcp_config.json](.agents/mcp_config.json) (runs compile-aware AI search)."
        else ""
      val mdocBullet =
        if hasMdoc then
          "\n*   **Mdoc documentation:** [docs/index.md](docs/index.md) (source markdown compiled by mdoc)."
        else ""
      val wtStartBullet =
        "\n*   **Start Worktree Script:** [scripts/worktree-start.scala](scripts/worktree-start.scala) (creates isolated task branch + worktree)."
      val wtFinishBullet =
        "\n*   **Finish Worktree Script:** [scripts/worktree-finish.scala](scripts/worktree-finish.scala) (commits, merges, and cleans up the worktree)."

      val newLlmSection =
        s"""## 3. LLM Configuration & Workspace Rules
           |
           |This project uses local LLM instructions and workspace rules tailored to the selected features:
           |*   **Antigravity/Gemini Rules:** [.agents/AGENTS.md](.agents/AGENTS.md)
           |*   **Cursor Rules:** [.cursorrules](.cursorrules)
           |*   **Global/Generic Rules:** [scala-rules.md](scala-rules.md)$mcpBullet$mdocBullet$wtStartBullet$wtFinishBullet
           |
           |All rules and guidelines are automatically kept in sync by the `Setup.scala` tool when features are added or removed.
           |""".stripMargin

      val startRulesIdx = content.indexOf("## 3. LLM Configuration")
      if startRulesIdx != -1 then
        content = content.substring(0, startRulesIdx) + newLlmSection
      else
        // Try fallback with the old section name
        val startRulesIdxFallback =
          content.indexOf("## 3. LLM Configuration & Shared Rules")
        if startRulesIdxFallback != -1 then
          content = content.substring(0, startRulesIdxFallback) + newLlmSection

      val finalContent = content.replace("$$targetDir", ".")
      os.write.over(file, finalContent)
      println(
        s"✓ Updated key commands and LLM instructions section in ${file.last}"
      )
