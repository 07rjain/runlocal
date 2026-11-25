plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.chaquo.python") }

// Use our own ABI selector. Android Studio's injected ABI property marks APKs testOnly.
val runlocalAbis = providers.gradleProperty("runlocal.abis").orElse("arm64-v8a,x86_64")
    .get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
require(runlocalAbis.isNotEmpty() && runlocalAbis.all { it in listOf("arm64-v8a", "x86_64") }) {
    "runlocal.abis must contain arm64-v8a and/or x86_64"
}

val releaseRepository = providers.gradleProperty("runlocal.repository").orElse("07rjain/runlocal").get()
require(releaseRepository.isEmpty() || Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(releaseRepository))
val signingPath = providers.environmentVariable("RUNLOCAL_KEYSTORE").orNull
val blueOutput = layout.buildDirectory.dir("blue-phonemizer")

android {
    namespace = "app.pocket.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pocket.local"
        minSdk = 31
        targetSdk = 36
        versionCode = 16
        versionName = "1.0.0-rc10"
        ndk { abiFilters += runlocalAbis }
        buildConfigField("String", "RELEASE_REPOSITORY", "\"$releaseRepository\"")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared"); targets += listOf("pocket_diffusion", "pocket_ocr") } }
    }
    // Chaquopy requires ndk.abiFilters, which AGP forbids alongside ABI splits.
    // Build a standalone APK for the requested ABI set and retain the phone output filename.
    applicationVariants.all {
        val variantName = name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (runlocalAbis.size == 1) "app-${runlocalAbis.single()}-$variantName.apk" else "app-$variantName.apk"
        }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    packaging { jniLibs.excludes += "**/x86/**" }
    sourceSets["main"].jniLibs.srcDir(blueOutput.map { it.dir("jni") })
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
val buildBluePhonemizer by tasks.registering {
    inputs.files(rootProject.fileTree("blue-phonemizer") { include("Cargo.*", "src/**") })
    inputs.file(rootProject.file("scripts/build-blue-phonemizer.sh"))
    inputs.file(rootProject.file("native-deps.lock"))
    inputs.property("abis", runlocalAbis)
    outputs.dir(blueOutput.map { it.dir("jni") })
    doLast {
        runlocalAbis.forEach { abi ->
            project.exec {
                commandLine("bash", rootProject.file("scripts/build-blue-phonemizer.sh"), rootProject.projectDir,
                    android.ndkDirectory, abi, blueOutput.get().asFile)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(buildBluePhonemizer) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Python 3.13 and these Android NumPy wheels support 16 KB memory pages.
chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(providers.gradleProperty("runlocal.buildPython").orElse("python3.13").get())
        pip { install("numpy==1.26.2") }
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation(project(":llama-runtime"))
    implementation(files("../third_party/android-adapters/sherpa.aar", "../third_party/android-adapters/tesseract.aar"))
    testImplementation("junit:junit:4.13.2")
}
