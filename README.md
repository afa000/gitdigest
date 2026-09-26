# GitDigest

[![build](https://github.com/afa000/gitdigest/actions/workflows/build.yml/badge.svg)](https://github.com/afa000/gitdigest/actions/workflows/build.yml)

A command-line tool that turns any Git repository into statistics, changelogs,
and AI-written release notes.

## Requirements

- **JDK 25**
- **`GITHUB_TOKEN`** — optional, and only used by `--github`. Without one you get
  GitHub's unauthenticated allowance of 60 requests an hour; with one, 5000.
- **`ANTHROPIC_API_KEY`** — optional, and only used by `notes`. Without one the
  release notes are assembled from the commits instead of written by Claude.

```
# PowerShell
$env:GITHUB_TOKEN = "ghp_..."
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

## Install

Download `gitdigest-<version>.jar` from the
[latest release](https://github.com/afa000/gitdigest/releases/latest) and run it:

```
java -jar gitdigest.jar stats .
```

That is the whole install. The jar bundles its dependencies, so there is
nothing else to fetch, and it needs **Java 25** - the same version the build
targets. On an older JVM it fails with `UnsupportedClassVersionError` before it
can print anything more helpful, which is a property of the JVM rather than a
choice.

### From source

```
.\gradlew shadowJar          # build\dist\gitdigest-<version>.jar
.\gradlew installDist        # launcher scripts in build\install\gitdigest
.\gradlew run --args="stats ."
```

The `installDist` launchers read `JAVA_HOME`, so point it at a JDK 25 if your
default is older:

```
# PowerShell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
```

## Commands

| Command | What it does |
|---|---|
| `gitdigest stats <repo>` | Commits per author, the busiest files, and activity by day and hour |
| `gitdigest changelog <repo>` | Commits between two revisions, grouped by type |
| `gitdigest notes <repo>` | Release notes for a commit range, written by Claude |

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
| `--github` | `changelog`, `notes` | Look up each commit's pull request on GitHub |
| `--tone formal\|casual` | `notes` | Voice to write in (default `formal`) |
| `--offline` | `notes` | Assemble the notes locally instead of calling Claude |
| `--jobs <n>` | `changelog` | How many of those lookups to run at once (default `8`) |

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

## Speed

Those lookups are almost entirely spent waiting on a socket, so they run in
parallel on virtual threads — one task per commit, with a semaphore capping how
many are in flight and a token bucket smoothing the burst.

```
40 commits, 200ms latency
  --jobs 1    8.5s
  --jobs 8    2.4s
  speedup     3.6x
```

Measured by `.\gradlew benchmark`, which runs the real client against a local
server that answers at a realistic latency. Both runs use the same code path;
only `--jobs` differs.

The ceiling is not the thread count. GitHub publishes a secondary limit of 900
points a minute for the REST API, and a read costs one point, so the pacer holds
the tool to 15 requests a second no matter how many threads are asking. Past
about nine commits that is what bounds the run — which is the right answer, not
a disappointing one. `--jobs 1` restores the old one-at-a-time behaviour.

Nothing here stretches the *hourly* quota, and the tool does not pretend
otherwise: when the allowance is gone it says so once, stops asking, keeps the
pull requests it already fetched, and prints the changelog anyway.

## Release notes

```
gitdigest notes . --from v1.0 --to v2.0 --github
```

Claude is given the commit subjects, the pull request titles and the
contributors, and writes the notes back as Markdown — streamed into the
terminal a fragment at a time rather than appearing all at once at the end.
`--tone casual` asks for a warmer voice; the rules that keep the notes truthful
apply either way.

**It works without an API key.** With no credentials configured, or with
`--offline`, the same data is assembled into notes locally: grouped, breaking
changes first, contributors credited, pull requests linked. Duller, complete,
free. The tool says which of the two you got.

If the call fails before any text arrives, it falls back to that local writer
and you still get a whole page. If it fails *partway*, it does not — a second
set of notes printed underneath the first half of another would be worse than
the truth, so it says the output is incomplete and exits non-zero.

Notes go to stdout and everything else to stderr, so this writes a clean file:

```
gitdigest notes . --from v1.0 > RELEASE_NOTES.md
```

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

## Releases

Versions follow [semantic versioning](https://semver.org). Pushing a tag
`vX.Y.Z` builds the jar, runs the whole suite, checks that the tag agrees with
the version the binary reports, and attaches the jar to a GitHub Release.

The release notes on that page are written by GitDigest itself, from the
commits between the previous tag and this one - in `--offline` mode, so that
shipping a release never depends on an API key or on a model being reachable.
That is the fallback from the `notes` command earning its keep.

## Tech

Java 25 (LTS) · Gradle · picocli · JGit · Jackson · virtual threads · GitHub REST API · Anthropic Java SDK
