plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.diffutils)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
