plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-projects"))
    implementation(project(":core-proxy"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
