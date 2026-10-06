@echo off
rem The Renova command line. Needs Java 21 or later; renova.jar sits beside this script.
java %RENOVA_JAVA_OPTS% -jar "%~dp0renova.jar" %*
