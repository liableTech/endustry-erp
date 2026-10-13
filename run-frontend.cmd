@echo off
rem Starts the Endustry ERP web app on http://localhost:5173 (needs the API running)
cd /d "%~dp0frontend"
if not exist node_modules call npm install
call npm run dev
