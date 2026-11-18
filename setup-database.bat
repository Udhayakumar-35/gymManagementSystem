@echo off
setlocal
cd /d "%~dp0"
where psql >nul 2>nul
if errorlevel 1 (
  if exist "C:\Program Files\PostgreSQL\18\bin\psql.exe" (
    set "PATH=C:\Program Files\PostgreSQL\18\bin;%PATH%"
  ) else (
    echo PostgreSQL command-line client (psql) was not found. Run database.sql in pgAdmin instead.
    exit /b 1
  )
)
echo Initializing gym_management database with PostgreSQL...
set PGPASSWORD=gymmanager
psql -U gymmanager -h localhost -p 5432 -d gym_management -f database.sql
if errorlevel 1 exit /b 1
echo Gym database and starter plans are ready in PostgreSQL.
endlocal
