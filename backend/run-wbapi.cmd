@echo off
rem Launches the wbapi module regardless of the caller's own working directory.
rem %~dp0 is this script's own directory (with trailing backslash) - robust because it's
rem resolved by cmd.exe from the script's location, not from whatever CWD invoked it.
rem
rem Explicit ".\" prefix on mvnw.cmd is required: this machine has
rem NoDefaultCurrentDirectoryInExePath=1 set, which disables cmd.exe's implicit search of
rem the current directory for executables/batch files - a bare "mvnw.cmd" silently fails
rem with "not recognized as an internal or external command" even when the file is right
rem there and CWD is correct (confirmed empirically).
rem
rem "-pl wbapi -am spring-boot:run" does NOT mean "only run wbapi" - Maven's reactor build
rem order for a bare goal invocation runs that SAME goal against every module -am pulls in,
rem including "workbench" (a library with no main class), which fails before wbapi is ever
rem reached (confirmed empirically: "Unable to find a suitable main class"). Fix: install
rem workbench's jar into the local repo first (idempotent, fast once up to date), then run
rem spring-boot:run against wbapi alone, with no -am, so workbench is resolved from the
rem local repo instead of being rebuilt-and-run in the same reactor pass.
cd /d "%~dp0"
call .\mvnw.cmd -q -pl workbench -am install -DskipTests
if errorlevel 1 exit /b 1
call .\mvnw.cmd -pl wbapi spring-boot:run
