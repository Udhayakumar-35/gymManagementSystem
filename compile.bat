@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\javac.exe" (
    set "PATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin;%PATH%"
  ) else (
    echo JDK 21 was not found. Install JDK 21 and add its bin folder to PATH.
    exit /b 1
  )
)
if not exist "lib\postgresql-42.7.4.jar" (
  echo Missing lib\postgresql-42.7.4.jar.
  exit /b 1
)
if not exist build mkdir build
javac --release 21 -encoding UTF-8 -cp "lib\postgresql-42.7.4.jar" -d build src\*.java
if errorlevel 1 exit /b 1
echo Build complete.
endlocal
