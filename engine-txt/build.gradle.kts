plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")); testImplementation(libs.junit) }
tasks.test { maxHeapSize = "64m"; testLogging { events("passed", "failed"); showStandardStreams = true } }
