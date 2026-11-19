@echo off
rem Builds ecoai-grid.jar with the plain JDK (17+). No Maven, no internet needed.
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java | findstr /v /i "\\ecoai\\boot\\" > sources.txt
javac -d out @sources.txt || exit /b 1
del sources.txt
xcopy /e /i /y src\main\resources out >nul
jar --create --file ecoai-grid.jar --main-class ecoai.Main -C out .
echo built ecoai-grid.jar
