# GitDigest

A command-line tool that turns any Git repository into statistics, changelogs,
and AI-written release notes.

## Requirements

- **JDK 25**
- **`GITHUB_TOKEN`** — optional, and only used by `--github`. Without one you get
  GitHub's unauthenticated allowance of 60 requests an hour; with one, 5000.

```
# PowerShell
$env:GITHUB_TOKEN = "ghp_..."
```

## Install

```
.\gradlew installDist
```

The launcher lands in `build\install\gitdigest\bin\`. To run without installing:

```
.\gradlew run --args="stats ."
```

## Commands

| Command | What it does |
|---|---|
| `gitdigest stats <repo>` | Commits per author, the busiest files, and activity by day and hour |
| `gitdigest changelog <repo>` | Commits between two revisions, grouped by type |
| `gitdigest notes <repo>` | AI-written release notes — not implemented yet |

`<repo>` defaults to the current directory. Every command takes `--help`.

## Options

| Flag | Applies to | Effect |
|---|---|---|
| `--format table\|json\|markdown` | both | How to render the result (default `table`) |
| `--author <text>` | both | Case-insensitive substring of the author name or email |
| `--since <YYYY-MM-DD>` | `stats` | Only commits on or after this date, inclusive |
| `--until <YYYY-MM-DD>` | `stats` | Only commits on or before this date, inclusive |
| `--from <rev>` | `changelog` | Start of the range, **excluded** — a tag, branch or hash |
| `--to <rev>` | `changelog` | End of the range, included (default `HEAD`) |
| `--github` | `changelog` | Look up each commit's pull request on GitHub |

`--from` is exclusive and `--to` is inclusive, matching `git log from..to`, so
`--from v1.0 --to v2.0` describes what changed *after* v1.0 shipped.

`--github` adds the pull request each commit arrived through, using its title in
place of the commit subject:

```
Bug Fixes
  0123456  Fix login timeout (#123, @contributor)
```

In `--format markdown` the number becomes a link. It is off by default because
it costs one GitHub request per commit. If GitHub is unreachable, the remote is
not on GitHub, or the rate limit runs out, the changelog still prints without
the extra data.

## Scripting

`--format json` makes the output machine-readable, and warnings go to stderr so
stdout stays clean:

```
gitdigest stats . --format json | jq .totalCommits
```

Colour is used only when stdout is a terminal, so redirecting to a file gives
plain text. `NO_COLOR` is honoured.

Exit codes: `0` on success — including a repository with no commits, which is
empty rather than broken; `1` with a one-line message on a bad path or an
unknown revision; `2` when the arguments themselves do not parse.

## Tech

Java 25 (LTS) · Gradle · picocli · JGit · Jackson · GitHub REST API · Claude API
