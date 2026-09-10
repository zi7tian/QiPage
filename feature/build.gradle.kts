plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace="local.readapp.feature"; compileSdk=36; defaultConfig { minSdk=30 }
    buildFeatures { compose=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies {
    testImplementation(libs.junit)
    implementation(project(":core")); implementation(project(":design"))
    implementation(libs.activity.compose); implementation(libs.lifecycle.compose); implementation(libs.viewmodel.compose)
    implementation(libs.coroutines.android)
}
