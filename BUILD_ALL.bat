@echo off
setlocal
cd /d "%~dp0"
echo Building WELLNESS...
call mvn -q -f wellness\pom.xml clean package -DskipTests
if errorlevel 1 goto :fail
echo Building CERTIFICATE EMAILER...
call mvn -q -f certificate\pom.xml clean package -DskipTests
if errorlevel 1 goto :fail
echo.
echo BUILD SUCCESS.
pause
exit /b 0
:fail
echo.
echo BUILD FAILED. Check the error above.
pause
exit /b 1
