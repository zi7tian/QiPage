plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
