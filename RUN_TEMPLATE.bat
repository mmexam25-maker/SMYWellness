@echo off
setlocal
cd /d "%~dp0"

REM ===== SET ONLY THIS NUMBER DIFFERENT ON EACH COMPUTER: 1 to 6 =====
set PC_ID=1
set TOTAL_PCS=6

REM With 6 PCs, start with 1 wellness Chrome per PC (6 total).
REM Later change to 2 only if memory + Google quota stay stable.
set MAX_PARALLEL_USERS=1

echo ===============================================
echo SMY MERGED MULTI-PC RUNNER
ECHO PC_ID=%PC_ID% of %TOTAL_PCS%
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
