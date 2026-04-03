plugins {
    kotlin("jvm")
    `java-library`
    id("io.spring.dependency-management")
}

val grpcVersion = "1.63.0"

dependencies {
    api(project(":proto"))
    api("io.grpc:grpc-kotlin-stub:1.4.1")
    api("io.grpc:grpc-netty-shaded:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.10")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions { jvmTarget = "21"; freeCompilerArgs += "-Xjsr305=strict" }
}
tasks.withType<Test> { useJUnitPlatform() }
