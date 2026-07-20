# Maintenance

## Scala Steward

This project has a valid `.scala-steward.conf`.

The `.mill-version` file is required so Scala Steward detects Mill 1.x and uses the current `--import` plugin path instead of the legacy `-p` flag.

Scala Steward expects `repos.md` entries in hosted forge form:

```text
- owner/repository
- owner/repository:branch
```

After this repository has a git remote, create a repos file with the hosted slug and run:

```bash
rtk coursier launch org.scala-steward:scala-steward-core_2.13:latest.release -- \
  --workspace /private/tmp/scala-purrism-steward-workspace \
  --repos-file /path/to/repos.md \
  --git-author-email steward@example.invalid \
  --git-ask-pass /path/to/askpass.sh \
  --forge-login <forge-login> \
  --repo-config .scala-steward.conf \
  --disable-sandbox
```

Local validation:

```bash
rtk coursier launch org.scala-steward:scala-steward-core_2.13:latest.release -- \
  validate-repo-config .scala-steward.conf
```
