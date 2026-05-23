@echo off
call build-jar.bat
if %errorlevel% neq 0 (
    echo Build failed. Aborting run.
    exit /b %errorlevel%
)

echo Starting mochadoom in headless mode...
java -jar src\mochadoom.jar -stdout %*
