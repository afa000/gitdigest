# A vhs script for the README's demo GIF: https://github.com/charmbracelet/vhs
#
#   scoop install vhs        (or: winget install charmbracelet.vhs)
#   vhs demo.tape            -> demo.gif
#
# Two things have to be true before recording, or the GIF shows the wrong thing:
#
#   1. `gitdigest` must be on PATH and must run. The installDist launcher reads
#      JAVA_HOME, so point it at a JDK 25 first:
#        $env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
#        .\gradlew installDist
#        $env:PATH = "$PWD\build\install\gitdigest\bin;$env:PATH"
#
#   2. DEMO_REPO must be a full clone with real history. A partial clone is
#      refused, and this repository's own history is too short to be
#      interesting. Something like picocli works well:
#        git clone https://github.com/remkop/picocli.git ../picocli
#
# The `notes` run below is deliberately --offline. Recording a demo that needs
# an API key means the demo breaks when the key expires, and it would spend
# money every time the GIF is re-recorded.

Output demo.gif

Set Shell pwsh
Set FontSize 15
Set Width 1100
Set Height 720
Set Padding 18
Set Theme "Catppuccin Mocha"
# Typed slowly enough to read, which is the whole point of a terminal GIF.
Set TypingSpeed 55ms

Hide
Type "cd ../picocli" Enter
Type "clear" Enter
Show

Type "gitdigest stats ."
Sleep 600ms
Enter
# Long enough to take in the bars without the viewer reaching for pause.
Sleep 5s

Type "clear" Enter
Type "gitdigest changelog . --from v4.7.6 --to v4.7.7 --format markdown | head -20"
Sleep 600ms
Enter
Sleep 5s

Type "clear" Enter
# The offline writer, so the GIF records the path everyone can run.
Type "gitdigest notes . --from v4.7.6 --to v4.7.7 --offline"
Sleep 600ms
Enter
Sleep 6s

Sleep 1s
