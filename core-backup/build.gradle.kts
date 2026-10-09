plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-projects"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
