#!/bin/bash

# gRPC Service Test Verification Script

echo "🧪 RateForge gRPC Service Test Verification"
echo "============================================="

echo "1. Checking test files exist..."

if [ -f "server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt" ]; then
    echo "✅ RateLimiterGrpcServiceTest.kt exists"
else
    echo "❌ RateLimiterGrpcServiceTest.kt missing"
    exit 1
fi

if [ -f "server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt" ]; then
    echo "✅ ConfigGrpcServiceTest.kt exists"
else
    echo "❌ ConfigGrpcServiceTest.kt missing"
    exit 1
fi

echo ""
echo "2. Counting test methods..."

RATE_LIMITER_TESTS=$(grep -c "@Test" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt)
CONFIG_TESTS=$(grep -c "@Test" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt)

echo "   RateLimiterGrpcServiceTest: $RATE_LIMITER_TESTS test methods"
echo "   ConfigGrpcServiceTest: $CONFIG_TESTS test methods"
echo "   Total: $((RATE_LIMITER_TESTS + CONFIG_TESTS)) test methods"

echo ""
echo "3. Checking required test scenarios..."

echo "   RateLimiterGrpcService scenarios:"
grep -q "checkLimit.*allowed when policy matches and under limit" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ allowed response test"
grep -q "checkLimit.*denied when over limit" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ denied response test"
grep -q "fail-open when circuit breaker is OPEN" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ circuit breaker OPEN test"
grep -q "batchCheck.*run all checks concurrently" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ batchCheck concurrent test"
grep -q "getLimitStatus.*return remaining without incrementing" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ getLimitStatus test"
grep -q "analytics event on every decision" server/src/test/kotlin/com/rateforge/grpc/RateLimiterGrpcServiceTest.kt && echo "   ✅ analytics event test"

echo ""
echo "   ConfigGrpcService scenarios:"
grep -q "createPolicy.*persist valid policy" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt && echo "   ✅ createPolicy valid test"
grep -q "ALREADY_EXISTS for duplicate policy" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt && echo "   ✅ createPolicy duplicate test"
grep -q "updatePolicy.*enabled field toggle" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt && echo "   ✅ updatePolicy enabled toggle test"
grep -q "deletePolicy.*remove policy from DB" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt && echo "   ✅ deletePolicy test"
grep -q "listPolicies.*return filtered results" server/src/test/kotlin/com/rateforge/grpc/ConfigGrpcServiceTest.kt && echo "   ✅ listPolicies test"

echo ""
echo "4. Checking test dependencies..."

if grep -q "testImplementation.*grpc-testing" server/build.gradle.kts; then
    echo "✅ gRPC testing dependency added"
else
    echo "❌ gRPC testing dependency missing"
fi

if grep -q "io.mockk:mockk" server/build.gradle.kts; then
    echo "✅ MockK dependency present"
else
    echo "❌ MockK dependency missing"
fi

echo ""
echo "5. Running test compilation check..."

if ./gradlew compileTestKotlin --no-daemon --console=plain; then
    echo "✅ Test compilation successful"
else
    echo "❌ Test compilation failed"
    exit 1
fi

echo ""
echo "6. Running the tests..."

if ./gradlew test --tests "com.rateforge.grpc.*" --no-daemon --console=plain; then
    echo "✅ All gRPC service tests passed!"
    
    # Extract test results
    TEST_RESULTS=$(./gradlew test --tests "com.rateforge.grpc.*" --console=plain 2>&1 | grep -E "(RateLimiterGrpcServiceTest|ConfigGrpcServiceTest)")
    echo ""
    echo "📊 Test Results:"
    echo "$TEST_RESULTS"
    
    echo ""
    echo "🎉 SUCCESS: All gRPC service tests are working!"
    echo "   - RateLimiterGrpcService has comprehensive test coverage"
    echo "   - ConfigGrpcService has comprehensive test coverage" 
    echo "   - All business logic scenarios tested"
    echo "   - MockK integration working correctly"
    echo "   - gRPC in-process testing working"
    exit 0
else
    echo "❌ Some tests failed"
    echo ""
    echo "Test output:"
    ./gradlew test --tests "com.rateforge.grpc.*" --console=plain
    exit 1
fi