## Local Environment Test Repositories (Proposals)

The following local repositories serve as prime candidates for testing `scalafix-purrism` rules in real-world Typelevel Scala codebases:

1. **`gh-tasks-llm-executor`**
    - Path: `/Users/viktorskalinins/IdeaProjects/my/gh-tasks-llm-executor`
    - Build Tool: Scala-CLI / Mill (Scala 3.8.4)
    - Stack: Cats Core `2.13.0`, Cats Effect `3.7.0`, `munit-cats-effect`, `arrowstep`
    - Notes: Active codebase already importing `scala-purrism-scalafix_3` via `project.scala`.

2. **`arrowstep`**
    - Path: `/Users/viktorskalinins/IdeaProjects/my/arrowstep`
    - Build Tool: Mill (Scala 3.8.4)
    - Stack: Cats Core `2.13.0`, Cats Effect `3.7.0`, `os-lib`, `ujson`
    - Notes: Pure Cats/Cats Effect library with `-Xsemanticdb` enabled in `build.mill`.

3. **`cctv-analyzer-scala`**
    - Path: `/Users/viktorskalinins/IdeaProjects/my/cctv-analyzer-scala`
    - Build Tool: SBT (Scala 3.8.3)
    - Stack: Cats Effect `3.7.0`, FS2 `3.13.0`, Http4s `0.23.34`, Circe `0.14.15`
    - Notes: Full Typelevel web application with `semanticdbEnabled := true` and `scalafixDependencies` configured.

4. **`ScalaSemanticMCP`**
    - Path: `/Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP`
    - Build Tool: Mill (Scala 3.8.4)
    - Stack: `mill-scalafix` (`0.6.0`), Scalameta `4.17.0`
    - Notes: Mill build using `mill-scalafix`, ideal for verifying rule integration on Mill builds.


