@echo off
setlocal
set "APP_HOME=%~dp0"
set "CROW_WRAPPER_PROJECT_DIR=%APP_HOME%"
if "%JAVA_HOME%"=="" (
  where java >nul 2>nul
  if errorlevel 1 (
    echo Java 17 or newer was not found. Install JDK 17+ or set JAVA_HOME.
    exit /b 1
  )
  set "JAVA_CMD=java"
) else (
  set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
"%JAVA_CMD%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" com.crow.wrapper.GradleBootstrap %*
exit /b %ERRORLEVEL%
