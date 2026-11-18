@echo off
setlocal
cd /d "%~dp0"
call compile.bat
if errorlevel 1 exit /b 1
if not exist "config\app.properties" (
  echo Missing config\app.properties. Copy config.example.properties there and fill in your PostgreSQL credentials.
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\java.exe" (
    set "PATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin;%PATH%"
  )
)
java -cp "build;lib\postgresql-42.7.4.jar" GymManagementServer
endlocal
