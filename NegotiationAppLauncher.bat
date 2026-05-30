@echo off
echo Starting Car Negotiation Platform v1.1...
echo.

REM Set Java classpath to include JADE JAR and the fat JAR
set CLASSPATH=lib\jade.jar;target\car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar

REM Run the application with the main class
java -cp "%CLASSPATH%" negotiation.Launcher

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Error: Application failed to start.
    echo Make sure:
    echo   1. Java is installed and in PATH
    echo   2. The project has been compiled: mvn clean package
    echo   3. lib\jade.jar exists
    echo.
    pause
)
