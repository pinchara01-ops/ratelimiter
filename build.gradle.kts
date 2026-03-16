plugins {
    kotlin("jvm") version "1.9.23" apply false
    kotlin("plugin.spring") version "1.9.23" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.rateforge"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
