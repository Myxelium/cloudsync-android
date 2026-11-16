plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":versioning"))
    implementation(libs.kotlinx.coroutines.core)
}
