# A vhs script for the README's demo GIF: https://github.com/charmbracelet/vhs
#
#   winget install charmbracelet.vhs    (plus tsl0922.ttyd and Gyan.FFmpeg)
#   vhs demo.tape            -> demo.gif
#
# Run it from a folder holding two full clones side by side:
#
#   git clone https://github.com/remkop/picocli.git
#   git clone https://github.com/afa000/gitdigest.git
#
# picocli is there for size - thousands of commits make stats worth watching.
# gitdigest is there for its conventional commits, which is what changelog and
# notes group by; picocli does not use them, so every change would land in
# "Other". A partial clone (--filter) of either is refused.
#
# `gitdigest` must be on PATH and must run. The installDist launcher reads
# JAVA_HOME, so point it at a JDK 25 first:
#   $env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
#   .\gradlew installDist
#   $env:PATH = "$PWD\build\install\gitdigest\bin;$env:PATH"
#
# The `notes` run is deliberately --offline. A demo that needs an API key
# breaks when the key expires, and would spend money every time it is
# re-recorded.
#
# Known issue: vhs 0.12 with ffmpeg 9 captures the frames but silently writes
# no GIF. If that happens, change Output to a directory ("Output frames/") and
# encode the frames yourself:
#   ffmpeg -framerate 50 -i frames/frame-text-%05d.png -framerate 50 -i frames/frame-cursor-%05d.png -filter_complex "[0][1]overlay,fps=20,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=none" demo.gif

Output demo.gif

# Windows PowerShell, which every Windows machine has; pwsh works too.
Set Shell powershell
Set FontSize 15
Set Width 1100
Set Height 720
Set Padding 18
Set Theme "Catppuccin Mocha"
# Typed slowly enough to read, which is the whole point of a terminal GIF.
Set TypingSpeed 45ms

Hide
# UTF-8 on both ends of the pipe, so contributor names with accents render
# as written: the console decodes UTF-8, and GITDIGEST_OPTS (read by the
# launcher) stops Java writing piped output as Cp1252.
Type "[Console]::OutputEncoding = [Text.Encoding]::UTF8; $env:GITDIGEST_OPTS = '-Dstdout.encoding=UTF-8'; clear" Enter
Show

# The first 34 lines are the header, the authors and the top files; the
# day and hour charts below them would push those off the screen.
Type "gitdigest stats picocli --since 2024-01-01 | Select-Object -First 34"
Sleep 500ms
Enter
# Long enough to take in the bars without the viewer reaching for pause.
Sleep 6s

Type "clear" Enter
Type "gitdigest changelog gitdigest"
Sleep 500ms
Enter
Sleep 5s

Type "clear" Enter
# The offline writer, so the GIF records the path everyone can run.
Type "gitdigest notes gitdigest --to v1.0.0 --offline"
Sleep 500ms
Enter
Sleep 6s
