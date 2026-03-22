@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "%~dp0"
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -Dorg.gradle.appname=gradlew -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain analytics-service:clean analytics-service:bootRun
