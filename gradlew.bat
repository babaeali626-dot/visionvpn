@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
    where gradle >nul 2>nul
    if %errorlevel%==0 (
        gradle wrapper --gradle-version 8.13
    ) else (
        echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing and gradle is not in PATH.
        exit /b 1
    )
)
"%JAVA_HOME%\bin\java.exe" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
