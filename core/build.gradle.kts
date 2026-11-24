plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android here: frames, DBC, log formats and analysis are plain Kotlin, so they are tested on any JVM.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    testImplementation(libs.junit)
}
