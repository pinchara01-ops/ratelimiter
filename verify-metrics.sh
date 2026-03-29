#!/bin/bash

# RateForge Metrics Verification Script
# Run this after starting the server to verify metrics endpoints

echo "🔍 RateForge Metrics Verification"
echo "=================================="

# Check if server is running
echo "1. Checking if RateForge server is running..."
if curl -f -s http://localhost:9091/actuator/health > /dev/null; then
    echo "✅ Server is running on management port 9091"
else
    echo "❌ Server is not responding on port 9091"
    echo "   Please start the server first using: ./gradlew server:bootRun"
    exit 1
fi

echo ""
echo "2. Testing /actuator/health endpoint..."
HEALTH_RESPONSE=$(curl -s http://localhost:9091/actuator/health)
echo "Response: $HEALTH_RESPONSE"

if echo "$HEALTH_RESPONSE" | grep -q '"status":"UP"'; then
    echo "✅ Health endpoint returns UP status"
else
    echo "❌ Health endpoint not working correctly"
fi

echo ""
echo "3. Testing /actuator/prometheus endpoint..."
PROMETHEUS_RESPONSE=$(curl -s http://localhost:9091/actuator/prometheus)

# Check for our custom metrics
echo "   Checking for custom RateForge metrics..."

METRICS_FOUND=0

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_decisions_total"; then
    echo "✅ rateforge_decisions_total metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_decision_latency"; then
    echo "✅ rateforge_decision_latency metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_circuit_breaker_state"; then
    echo "✅ rateforge_circuit_breaker_state metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_analytics_queue_depth"; then
    echo "✅ rateforge_analytics_queue_depth metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_analytics_dropped_events"; then
    echo "✅ rateforge_analytics_dropped_events metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_policy_cache_size"; then
    echo "✅ rateforge_policy_cache_size metric found"
    ((METRICS_FOUND++))
fi

if echo "$PROMETHEUS_RESPONSE" | grep -q "rateforge_hotkey_pre_denied"; then
    echo "✅ rateforge_hotkey_pre_denied metric found"
    ((METRICS_FOUND++))
fi

echo ""
echo "📊 Metrics Summary:"
echo "   Custom metrics found: $METRICS_FOUND/7"

if [ $METRICS_FOUND -eq 7 ]; then
    echo "✅ ALL CUSTOM METRICS ARE PRESENT!"
    echo ""
    echo "🎉 SUCCESS: RateForge metrics implementation is working correctly!"
    echo ""
    echo "📈 Available endpoints:"
    echo "   - Health: http://localhost:9091/actuator/health"
    echo "   - Metrics (JSON): http://localhost:9091/actuator/metrics"  
    echo "   - Prometheus: http://localhost:9091/actuator/prometheus"
    echo ""
    echo "🚀 Ready for deployment! The fly.toml metrics configuration will work."
    exit 0
else
    echo "❌ Some metrics are missing. Check server logs for errors."
    exit 1
fi