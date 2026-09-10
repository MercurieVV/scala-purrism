package fix.architecture

import fix.RequireArrowArchitectureConfig

/** Whether a file is in scope for `RequireArrowArchitecture`, per its
  * `packages`/`paths` config. A file matches if EITHER list has an entry that
  * matches. Both lists empty (the default) means nothing matches -- no
  * accidental whole-project scans.
  *
  * `**` glob support is a package/path *prefix* match, not a general glob
  * engine: `"com.foo.wiring.**"` matches the package `com.foo.wiring` and every
  * subpackage of it. This is deliberately simpler than a full glob library --
  * YAGNI until a real config needs more.
  */
object ArchitectureScope {

  def inScope(
      pkg: Option[String],
      path: String,
      config: RequireArrowArchitectureConfig
  ): Boolean =
    config.packages.exists(matchesPackage(pkg, _)) ||
      config.paths.exists(matchesPath(path, _))

  private def matchesPackage(pkg: Option[String], glob: String): Boolean =
    pkg.exists { p =>
      if (glob.endsWith(".**")) {
        val prefix = glob.dropRight(3)
        p == prefix || p.startsWith(prefix + ".")
      } else p == glob
    }

  private def matchesPath(path: String, glob: String): Boolean =
    if (glob.endsWith("/**")) path.startsWith(glob.dropRight(3))
    else path == glob
}
