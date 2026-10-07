@echo off
rem Runs the Renova CLI from a source checkout (build first with "mvn package" from the repository root).
java %RENOVA_JAVA_OPTS% -jar "%~dp0..\target\renova.jar" %*
