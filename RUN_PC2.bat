@echo off
setlocal
cd /d "%~dp0"

REM ===== THIS PC NUMBER MUST BE UNIQUE: 1 to 11 =====
set PC_ID=2
set TOTAL_PCS=11

REM Start with one wellness user per PC. Increase to 2 only after stable testing.
set MAX_PARALLEL_USERS=1

echo ===============================================
echo SMY MERGED 11-PC RUNNER
echo PC_ID=%PC_ID% of %TOTAL_PCS%
echo Wellness sheet=PC%PC_ID%
echo Wellness users on this PC=%MAX_PARALLEL_USERS%
echo ===============================================

if not exist wellness\target\yoga-scheduler-1.0-SNAPSHOT.jar (
  echo Wellness jar not found. Run BUILD_ALL.bat first.
  pause
  exit /b 1
)
if not exist certificate\target\smy-certificate-emailer-1.0.jar (
  echo Certificate jar not found. Run BUILD_ALL.bat first.
  pause
  exit /b 1
)

start "SMY WELLNESS PC%PC_ID%" cmd /k "cd /d %~dp0wellness && set PC_ID=%PC_ID% && set TOTAL_PCS=%TOTAL_PCS% && set MAX_PARALLEL_USERS=%MAX_PARALLEL_USERS% && java -jar target\yoga-scheduler-1.0-SNAPSHOT.jar"
start "SMY CERTIFICATE PC%PC_ID%" cmd /k "cd /d %~dp0certificate && set PC_ID=%PC_ID% && set TOTAL_PCS=%TOTAL_PCS% && java -jar target\smy-certificate-emailer-1.0.jar"

exit /b 0
