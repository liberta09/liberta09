import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    apply(plugin = "kotlin-android")

    extensions.configure<LibraryExtension> {
        compileSdk = 34
        namespace = "com.liberta09"
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    dependencies {
        val cloudstream by configurations
        cloudstream("com.github.recloudstream.cloudstream:library:pre-release")

        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.13.0")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
