@echo off
cd /d "%~dp0"
echo ===================================================
echo  Pushing Samsung NX1000 Photo Transfer to GitHub
echo  Repository: https://github.com/FL4K901/samsung-nx1000-photo-transfer
echo ===================================================
echo.
git push origin main --tags
if %errorlevel% equ 0 (
    echo.
    echo ===================================================
    echo  SUCCESS! Successfully uploaded v1.2.0 to GitHub!
    echo ===================================================
) else (
    echo.
    echo [ERROR] Push failed. If prompted, please authenticate with GitHub.
)
pause
