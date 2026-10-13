@echo off
rem Starts the Endustry ERP API on http://localhost:8080
cd /d "%~dp0backend"
call "%~dp0backend\mvnw.cmd" -q spring-boot:run
