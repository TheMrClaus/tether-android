// Theme, tokens, fonts (res/font), shared components and display formatting.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // T3.3: JVM screenshot tests (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.core.designsystem"
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric (screenshot tests) needs the merged resources: fonts, the test activity.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// T3.3: goldens live in the source tree (checked in), one PNG per primitive × state × skin × size.
// recordRoborazziDebug rewrites them; verifyRoborazziDebug (CI) fails on any difference.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

// PLAN D9 / T3.1: GeneratedTokens.kt is generated from the vendored token export by
// :tools:design-tokens and checked in. generateDesignTokens rewrites it;
// verifyDesignTokens regenerates into build/ and fails on any difference (wired into check).
val designTokenGenerator: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
val designTokensJson = rootProject.layout.projectDirectory.file("parity-corpus/tokens/design-tokens.json")
val generatedTokensKt = layout.projectDirectory.file("src/main/java/com/tether/app/ui/theme/GeneratedTokens.kt")
val designTokensMain = "com.tether.tools.designtokens.DesignTokenGeneratorKt"

tasks.register<JavaExec>("generateDesignTokens") {
    group = "tether"
    description = "Regenerates GeneratedTokens.kt from parity-corpus/tokens/design-tokens.json."
    classpath = designTokenGenerator
    mainClass.set(designTokensMain)
    inputs.file(designTokensJson).withPropertyName("designTokensJson")
    outputs.file(generatedTokensKt).withPropertyName("generatedTokensKt")
    args("generate", designTokensJson.asFile.absolutePath, generatedTokensKt.asFile.absolutePath)
}

val verifyDesignTokens = tasks.register<JavaExec>("verifyDesignTokens") {
    group = "verification"
    description = "Fails when GeneratedTokens.kt differs from a fresh generation of the token JSON."
    classpath = designTokenGenerator
    mainClass.set(designTokensMain)
    val temp = layout.buildDirectory.file("design-tokens/GeneratedTokens.kt")
    inputs.file(designTokensJson).withPropertyName("designTokensJson")
    inputs.file(generatedTokensKt).withPropertyName("generatedTokensKt")
    outputs.file(temp).withPropertyName("regenerated")
    args(
        "verify",
        designTokensJson.asFile.absolutePath,
        generatedTokensKt.asFile.absolutePath,
        temp.get().asFile.absolutePath,
    )
}
tasks.named("check") { dependsOn(verifyDesignTokens) }

// Token tests read the JSON and drive the generator against temp copies of it.
tasks.withType<Test>().configureEach {
    inputs.file(designTokensJson).withPropertyName("designTokensJson")
    inputs.file(generatedTokensKt).withPropertyName("generatedTokensKt")
    systemProperty("tether.designTokensJson", designTokensJson.asFile.absolutePath)
    systemProperty("tether.generatedTokensKt", generatedTokensKt.asFile.absolutePath)
}

dependencies {
    // ThemeMode (the persisted appearance preference) is part of TetherTheme's API.
    api(project(":core:data"))
    api(libs.lucide.icons)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core)
    // ta-coik.20: state/RetainedSecret.kt rides the activity's ViewModel store.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // The shared video player (ui/video): MediaVideoPlayer's coroutines, the host's lifecycle observer.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)

    designTokenGenerator(project(":tools:design-tokens"))

    testImplementation(libs.junit)
    testImplementation(project(":tools:design-tokens"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
