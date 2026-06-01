Write-Host "Cleaning old class files..."
if (Test-Path classes) {
    Remove-Item -Recurse -Force classes
}
New-Item -ItemType Directory -Force -Path classes | Out-Null

Write-Host "Compiling Java sources..."
javac -d classes -sourcepath src src/mochadoom/Engine.java
if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation failed!"
    exit $LASTEXITCODE
}

Write-Host "Packaging jar file..."
Copy-Item src\Manifest.txt classes\
Set-Location classes
jar cmf Manifest.txt mochadoom.jar .
Move-Item -Path mochadoom.jar -Destination ..\src\mochadoom.jar -Force
Set-Location ..

Write-Host "Build successful! src\mochadoom.jar created."
Write-Host ""
Write-Host "Run (WebSocket mode with browser music):"
Write-Host "  java --add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED -jar src\mochadoom.jar -websocket 8080"
