plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":merge"))
    implementation(project(":versioning"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
