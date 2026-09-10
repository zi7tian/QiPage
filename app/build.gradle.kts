plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace="local.readapp"; compileSdk=36
    defaultConfig {
        applicationId="local.readapp"; minSdk=30; targetSdk=36; versionCode=400; versionName="0.4.0-p4"
        testInstrumentationRunner="local.readapp.test.P4Instrumentation"
    }
    signingConfigs { create("localTest") { storeFile=rootProject.file(".tools/p0.keystore"); storePassword="android"; keyAlias="p0"; keyPassword="android" } }
    buildTypes { release {
        isMinifyEnabled=true; isShrinkResources=true; signingConfig=signingConfigs.getByName("localTest")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
    } }
    buildFeatures { compose=true }
    testBuildType="release"
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled=true }
    kotlinOptions { jvmTarget="17" }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("fixtures/generated"))
}
dependencies {
    implementation(project(":engine-epub"))
    implementation(project(":core")); implementation(project(":data")); implementation(project(":engine-txt")); implementation(project(":feature"))
    implementation(libs.activity.compose); implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
}




