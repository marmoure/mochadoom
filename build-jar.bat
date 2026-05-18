@echo off
setlocal

echo Cleaning old class files...
if exist classes rmdir /s /q classes
mkdir classes

echo Compiling Java sources...
javac -d classes -sourcepath src src/mochadoom/Engine.java
if %errorlevel% neq 0 (
    echo Compilation failed!
    exit /b %errorlevel%
)

echo Packaging jar file...
copy src\Manifest.txt classes\ >nul
cd classes
jar cmf Manifest.txt mochadoom.jar .
move /Y mochadoom.jar ..\src\mochadoom.jar >nul
cd ..

echo Build successful! src\mochadoom.jar created.
