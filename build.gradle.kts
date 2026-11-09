// LiteRT-LM 0.18.1 ships Kotlin 2.4 metadata; use a compatible shrinker.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:9.1.31") }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("com.chaquo.python") version "17.2.0" apply false
}
