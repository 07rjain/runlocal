plugins { id("com.android.library") }

android {
    namespace = "app.pocket.local.llama"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 31
        ndk {
            abiFilters += providers.gradleProperty("runlocal.abis").orElse("arm64-v8a,x86_64")
                .get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared"); targets += "pocket_llama" }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
