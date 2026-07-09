import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gdxVersion = "1.13.1"
val natives: Configuration by configurations.creating

// Release signing. Local builds read keystore.properties (gitignored); CI reads
// the equivalent environment variables. With neither present the release build
// is produced unsigned, so the project still builds for anyone without the key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)
val releaseStoreFile: String? = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "edge.roll"
    compileSdk = 35

    defaultConfig {
        applicationId = "edge.roll"
        minSdk = 28          // Android 9 (Pie) and up
        targetSdk = 35
        versionCode = 8
        versionName = "1.1.1-beta3"
    }

    // Keep release APKs free of Google's dependency-metadata signing block,
    // which F-Droid rejects (and which breaks reproducible builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].jniLibs.srcDirs("libs")

    lint {
        // False positive: the adaptive launcher icon must stay in mipmap-anydpi-v26.
        // Merging it into mipmap-anydpi (as ObsoleteSdkInt suggests) makes AAPT fail
        // to find the resource, breaking the build.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
}

// Extract libGDX native .so files into jniLibs before they are merged.
tasks.register("copyAndroidNatives") {
    doFirst {
        natives.files.forEach { jar ->
            val abi = jar.nameWithoutExtension.substringAfterLast("natives-")
            val outputDir = file("libs/$abi")
            outputDir.mkdirs()
            copy {
                from(zipTree(jar))
                into(outputDir)
                include("*.so")
            }
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn("copyAndroidNatives")
}
