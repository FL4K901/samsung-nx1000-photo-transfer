$ErrorActionPreference = "Stop"

$BUILD_TOOLS = "C:\Users\User\AppData\Local\Android\Sdk\build-tools\36.0.0"
$PLATFORM = "C:\Users\User\AppData\Local\Android\Sdk\platforms\android-34\android.jar"
$JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"
$KEYSTORE = "C:\Users\User\.android\debug.keystore"
$PROJECT_DIR = $PSScriptRoot

Set-Location $PROJECT_DIR

Write-Host "1. Cleaning and creating build directories..."
if (Test-Path "build") { Remove-Item -Recurse -Force "build" }
New-Item -ItemType Directory -Force "build" | Out-Null
New-Item -ItemType Directory -Force "build\classes" | Out-Null
New-Item -ItemType Directory -Force "build\dex" | Out-Null
New-Item -ItemType Directory -Force "release" | Out-Null

Write-Host "2. Compiling resources with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" compile --dir app\src\main\res -o build\res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "3. Linking APK with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" link -o build\base.apk -I $PLATFORM --manifest app\src\main\AndroidManifest.xml build\res.zip --java app\src\main\java --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "4. Compiling Java sources with javac..."
$javaFiles = Get-ChildItem -Path "app\src\main\java\com\nx\mobilelink\*.java" | ForEach-Object { $_.FullName }
& "$JAVA_HOME\bin\javac.exe" --release 17 -encoding UTF-8 -cp "$PLATFORM" -d build\classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

# Clean generated R.java from src tree so it stays clean
Remove-Item -Path "app\src\main\java\com\nx\mobilelink\R.java" -Force -ErrorAction SilentlyContinue

Write-Host "5. Packaging classes and dexing with d8..."
& "$JAVA_HOME\bin\jar.exe" -cf "build\classes.jar" -C "build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar classes failed" }
& "$BUILD_TOOLS\d8.bat" --release --min-api 21 --lib "$PLATFORM" --output build\dex build\classes.jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "6. Adding classes.dex to base.apk..."
Set-Location "$PROJECT_DIR\build\dex"
& "$JAVA_HOME\bin\jar.exe" -uf "$PROJECT_DIR\build\base.apk" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar uf failed" }
Set-Location $PROJECT_DIR

Write-Host "7. Aligning APK with zipalign..."
& "$BUILD_TOOLS\zipalign.exe" -p -f 4 build\base.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "8. Signing APK with apksigner (v1 + v2)..."
& "$BUILD_TOOLS\apksigner.bat" sign --min-sdk-version 21 --v1-signing-enabled true --v2-signing-enabled true --ks $KEYSTORE --ks-pass pass:android --key-pass pass:android --out release\NX-MobileLink-v1.4.3.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "9. Verifying signature..."
& "$BUILD_TOOLS\apksigner.bat" verify -v release\NX-MobileLink-v1.4.3.apk

Copy-Item -Force "release\NX-MobileLink-v1.4.3.apk" "release\NX-MobileLink.apk"
Copy-Item -Force "release\NX-MobileLink-v1.4.3.apk" "C:\Users\User\Downloads\NX-MobileLink.apk"
Copy-Item -Force "release\NX-MobileLink-v1.4.3.apk" "C:\Users\User\Downloads\NX-MobileLink-v1.4.3.apk"
Copy-Item -Force "release\NX-MobileLink-v1.4.3.apk" "c:\Users\User\Desktop\mobilelinknew\NX-MobileLink.apk"

Write-Host "BUILD SUCCESSFUL! Output: release\NX-MobileLink-v1.4.3.apk (Size: $((Get-Item release\NX-MobileLink-v1.4.3.apk).Length) bytes)"
