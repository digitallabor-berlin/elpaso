import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version metadata. `versionName` is set manually in version.properties; `buildNumber`
// auto-increments on real builds (assemble/bundle/install). The bump runs at
// configuration time so the in-flight build picks up the new number — guarded by
// inspecting startParameter.taskNames so a plain `gradle help` or IDE sync doesn't
// move the counter. The bumped file is written back to disk; commit it like any
// other source file.
val versionPropsFile = file("version.properties")
val versionProps =
    Properties().apply {
        versionPropsFile.inputStream().use { load(it) }
    }
val isBuildingArtifact =
    gradle.startParameter.taskNames.any { name ->
        val lower = name.lowercase()
        lower.contains("assemble") || lower.contains("bundle") || lower.contains("install")
    }
val currentBuildNumber =
    (versionProps.getProperty("buildNumber")?.toIntOrNull() ?: 1).let {
        if (isBuildingArtifact) it + 1 else it
    }
if (isBuildingArtifact) {
    versionProps.setProperty("buildNumber", currentBuildNumber.toString())
    versionPropsFile.outputStream().use { versionProps.store(it, null) }
}
val appVersionName: String = versionProps.getProperty("versionName") ?: "0.0.0"

android {
    namespace = "dev.digitallabor.elpaso.wallet"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.digitallabor.elpaso.wallet"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = currentBuildNumber
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(
            libs.versions.jdk
                .get()
                .toInt(),
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    // BouncyCastle 1.83 multi-release jars each ship an OSGi manifest at this path.
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                )
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.coil.svg)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.credentials.registry.provider)
    implementation(libs.androidx.credentials.registry.provider.play.services)
    implementation(libs.androidx.credentials.registry.digitalcredentials.mdoc)
    implementation(libs.androidx.credentials.registry.digitalcredentials.sdjwtvc)
    implementation(libs.androidx.credentials.registry.digitalcredentials.openid)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    implementation(libs.bundles.koin)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bundles.ktor)

    implementation(libs.eudi.openid4vci)
    implementation(libs.eudi.openid4vp)

    implementation(libs.multipaz)

    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
