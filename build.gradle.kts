import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file. Plugin versions are managed in gradle/libs.versions.toml.
// Note: AGP 9.x has built-in Kotlin, so org.jetbrains.kotlin.android is not applied
// (it is rejected by AGP 9 unless android.builtInKotlin=false).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Shared module convention (PLAN D7): every module compiles Java/Kotlin to 17,
// and every Android library module uses the same SDK levels as :app. Each module's
// own build file only declares its plugins, namespace and dependencies.
subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    // T2.1 Revision 7: every Compose (UI) module treats the v128 projection tree
    // (com.tether.app.protocol.tree.*, immutable persistent JsValues) as stable, so an
    // untouched subtree lets Compose skip. Core stays free of any Compose dependency.
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 37
            compileSdkMinor = 2
            defaultConfig { minSdk = 34 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
