@echo off
setlocal
set MAVEN_VERSION=3.9.9
set WRAPPER_DIR=%~dp0.mvn\wrapper
set MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%
set MAVEN_BIN=%MAVEN_HOME%\bin\mvn.cmd

if not exist "%MAVEN_BIN%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $dir='%WRAPPER_DIR%'; $version='%MAVEN_VERSION%'; $zip=Join-Path $dir ('apache-maven-' + $version + '-bin.zip'); New-Item -ItemType Directory -Force -Path $dir | Out-Null; Invoke-WebRequest -Uri ('https://archive.apache.org/dist/maven/maven-3/' + $version + '/binaries/apache-maven-' + $version + '-bin.zip') -OutFile $zip; Expand-Archive -Force -Path $zip -DestinationPath $dir"
  if errorlevel 1 exit /b %errorlevel%
)

call "%MAVEN_BIN%" %*
endlocal
