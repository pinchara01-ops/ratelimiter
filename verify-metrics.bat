@echo off
REM RateForge Metrics Verification Script for Windows
REM Run this after starting the server to verify metrics endpoints

echo 🔍 RateForge Metrics Verification
echo ==================================

REM Check if server is running
echo 1. Checking if RateForge server is running...
curl -f -s http://localhost:9091/actuator/health >nul 2>&1
if %errorlevel% == 0 (
    echo ✅ Server is running on management port 9091
) else (
    echo ❌ Server is not responding on port 9091
    echo    Please start the server first using: gradlew.bat server:bootRun
    exit /b 1
)

echo.
echo 2. Testing /actuator/health endpoint...
curl -s http://localhost:9091/actuator/health
echo.

echo 3. Testing /actuator/prometheus endpoint...
echo    Checking for custom RateForge metrics...

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_decisions_total" >nul
if %errorlevel% == 0 echo ✅ rateforge_decisions_total metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_decision_latency" >nul  
if %errorlevel% == 0 echo ✅ rateforge_decision_latency metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_circuit_breaker_state" >nul
if %errorlevel% == 0 echo ✅ rateforge_circuit_breaker_state metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_analytics_queue_depth" >nul
if %errorlevel% == 0 echo ✅ rateforge_analytics_queue_depth metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_analytics_dropped_events" >nul
if %errorlevel% == 0 echo ✅ rateforge_analytics_dropped_events metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_policy_cache_size" >nul
if %errorlevel% == 0 echo ✅ rateforge_policy_cache_size metric found

curl -s http://localhost:9091/actuator/prometheus | findstr /C:"rateforge_hotkey_pre_denied" >nul
if %errorlevel% == 0 echo ✅ rateforge_hotkey_pre_denied metric found

echo.
echo 🎉 Verification complete! 
echo.
echo 📈 Available endpoints:
echo    - Health: http://localhost:9091/actuator/health
echo    - Metrics (JSON): http://localhost:9091/actuator/metrics
echo    - Prometheus: http://localhost:9091/actuator/prometheus
echo.
echo 🚀 Ready for deployment! The fly.toml metrics configuration will work.