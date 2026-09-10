plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace="local.readapp.design"; compileSdk=36
    defaultConfig { minSdk=30 }; buildFeatures { compose=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { api(libs.compose.material3); api(libs.compose.foundation); api(libs.compose.ui) }
