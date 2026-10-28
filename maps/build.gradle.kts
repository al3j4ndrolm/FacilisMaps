plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.al3j4ndrolm.facilis.maps"
    compileSdk {
        version = release(37)
    }
    resourcePrefix = "facilis_maps_"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.facilis.permission)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.startup)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "maps"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
