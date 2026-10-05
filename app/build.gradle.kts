import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9+ has built-in Kotlin; org.jetbrains.kotlin.android must NOT be applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // T3.4: JVM screenshot tests of the debug-only Component Gallery (recordRoborazziDebug /
    // verifyRoborazziDebug); goldens in src/testDebug/screenshots.
    alias(libs.plugins.roborazzi)
}

// Release signing comes from the environment (CI decodes the keystore from a
// repository secret — see .github/workflows/android-release.yml). When the
// variables are absent (local builds, dry_run) the release build stays
// unsigned; nothing key-related ever lives in the repo.
val releaseStoreFile: String? = System.getenv("TETHER_RELEASE_STORE_FILE")

android {
    namespace = "com.tether.app"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.tether.app"
        minSdk = 34
        targetSdk = 37
        versionCode = 16
        versionName = "0.6.0"
        // T13.1 rollback flag (SYNC_DESIGN §2.6): the Room journal mirror. Off with
        // `-Ptether.mirrorEnabled=false`; an existing mirror is then deleted with its keys.
        buildConfigField(
            "boolean",
            "MIRROR_ENABLED",
            providers.gradleProperty("tether.mirrorEnabled").orElse("true").get().toBooleanStrict().toString(),
        )
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("TETHER_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("TETHER_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("TETHER_RELEASE_KEY_PASSWORD")
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

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME: the D13 update check compares it to the latest release tag.
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        // Robolectric needs the merged manifest/resources/assets on the
        // classpath for the test variant.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// T3.4: the Component Gallery lives in src/debug (never in release, see verifyGalleryNotInRelease); its
// goldens are checked in, verifyRoborazziDebug (CI) fails on any changed pixel.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/testDebug/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

/**
 * T3.4: proves the debug-only Component Gallery is absent from what release ships — no class of
 * the `com.tether.app.gallery` package among the release variant's compiled classes (dirs and
 * jars) and no gallery activity in the release merged manifest. Needs no signing secrets.
 */
abstract class VerifyGalleryNotInRelease : DefaultTask() {
    @get:InputFiles abstract val classJars: ListProperty<RegularFile>
    @get:InputFiles abstract val classDirs: ListProperty<Directory>
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val prefix = "com/tether/app/gallery/"
        val leaks = mutableListOf<String>()
        var scanned = 0
        classDirs.get().forEach { dir ->
            dir.asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                scanned++
                val rel = f.relativeTo(dir.asFile).invariantSeparatorsPath
                if (rel.startsWith(prefix)) leaks += rel
            }
        }
        classJars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                    scanned++
                    if (e.name.startsWith(prefix)) leaks += e.name
                }
            }
        }
        val manifest = mergedManifest.get().asFile.readText()
        if ("gallery" in manifest.lowercase()) leaks += "merged manifest mentions the gallery"
        check(scanned > 0) { "no release classes were scanned" }
        check(leaks.isEmpty()) { "Component Gallery leaked into release: $leaks" }
        report.get().asFile.writeText("release classes scanned: $scanned; gallery classes: 0; manifest: clean\n")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val verify = tasks.register<VerifyGalleryNotInRelease>("verifyGalleryNotInRelease") {
            group = "verification"
            description = "Fails when the debug-only Component Gallery reaches the release variant."
            report.set(layout.buildDirectory.file("reports/gallery-not-in-release.txt"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(verify)
            .toGet(ScopedArtifact.CLASSES, VerifyGalleryNotInRelease::classJars, VerifyGalleryNotInRelease::classDirs)
        verify.configure { mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)) }
    }
}
tasks.named("check") { dependsOn("verifyGalleryNotInRelease") }

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:net"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:shell"))
    // Previews.kt renders sidebar and chat components side by side.
    implementation(project(":feature:chat"))
    implementation(project(":feature:sidebar"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.lucide.icons)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)

    // Firebase Cloud Messaging. The BOM aligns versions; only the messaging
    // artifact is used. No google-services Gradle plugin is applied — Firebase
    // is initialised programmatically in TetherApp.onCreate from env-supplied
    // FirebaseOptions (see PushController), so no google-services.json is ever
    // checked in. When the env vars are absent, Firebase stays uninitialised
    // and the push subsystem reports "not configured" at runtime.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // T3.4 gallery screenshot tests (compose rule + Roborazzi capture).
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // An application's unit tests use its own merged manifest: the compose rule's host activity
    // must be in the DEBUG manifest (debug only; release never sees it).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
