plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningEnvironment = mapOf(
    "storeFile" to "LOCALDROP_RELEASE_STORE_FILE",
    "storePassword" to "LOCALDROP_RELEASE_STORE_PASSWORD",
    "keyAlias" to "LOCALDROP_RELEASE_KEY_ALIAS",
    "keyPassword" to "LOCALDROP_RELEASE_KEY_PASSWORD"
)
val releaseSigningValues = releaseSigningEnvironment.mapValues { (_, name) ->
    providers.environmentVariable(name).orNull
}
val releaseSigningStoreFileExists = releaseSigningValues["storeFile"]
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it).isFile } == true
val releaseSigningConfigured =
    releaseSigningValues.values.all { !it.isNullOrBlank() } && releaseSigningStoreFileExists
val missingReleaseSigningVariables = releaseSigningEnvironment
    .filterKeys { releaseSigningValues[it].isNullOrBlank() }
    .values
    .toMutableList()
    .apply {
        if (!releaseSigningValues.getValue("storeFile").isNullOrBlank() && !releaseSigningStoreFileExists) {
            add("keystore file referenced by LOCALDROP_RELEASE_STORE_FILE")
        }
    }

android {
    namespace = "com.samir.localdrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.samir.localdrop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("storeFile")!!)
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "packageReleaseBundle") {
        doFirst {
            if (!releaseSigningConfigured) {
                throw GradleException(
                    "Release packaging requires signing. Set these environment variables outside source control: " +
                        missingReleaseSigningVariables.joinToString(", ")
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.21")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
