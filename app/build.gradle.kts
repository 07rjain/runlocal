plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Use our own ABI selector. Android Studio's injected ABI property marks APKs testOnly.
val runlocalAbis = providers.gradleProperty("runlocal.abis").orElse("arm64-v8a,x86_64")
    .get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
require(runlocalAbis.isNotEmpty() && runlocalAbis.all { it in listOf("arm64-v8a", "x86_64") }) {
    "runlocal.abis must contain arm64-v8a and/or x86_64"
}

val releaseRepository = providers.gradleProperty("runlocal.repository").orElse("07rjain/runlocal").get()
require(releaseRepository.isEmpty() || Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(releaseRepository))
val signingPath = providers.environmentVariable("RUNLOCAL_KEYSTORE").orNull

android {
    namespace = "app.pocket.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pocket.local"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.0-rc3"
        buildConfigField("String", "RELEASE_REPOSITORY", "\"$releaseRepository\"")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared"); targets += "pocket_diffusion" } }
    }
    splits { abi { isEnable = true; reset(); include(*runlocalAbis.toTypedArray()); isUniversalApk = false } }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (signingPath != null) create("distribution") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("RUNLOCAL_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("RUNLOCAL_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("RUNLOCAL_KEY_PASSWORD").orNull
        }
    }
    buildTypes { release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        if (signingPath != null) signingConfig = signingConfigs.getByName("distribution")
    } }
    testOptions { unitTests.all { test ->
        providers.gradleProperty("nativeSmokeDir").orNull?.let { path ->
            test.jvmArgs("-Djava.library.path=$path")
            test.systemProperty("pocket.native.enabled", "true")
        }
        providers.gradleProperty("nativeSmokeModel").orNull?.let { test.systemProperty("pocket.test.model", it) }
        providers.gradleProperty("liveModelCheck").orNull?.let { test.systemProperty("runlocal.liveModels", it) }
    } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
