package com.tether.app.ui.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The vendored token export and the checked-in generated file (paths from the Gradle test task). */
object TokenCorpus {
    val jsonFile: File = File(
        System.getProperty("tether.designTokensJson") ?: "../../parity-corpus/tokens/design-tokens.json",
    )
    val generatedFile: File = File(
        System.getProperty("tether.generatedTokensKt")
            ?: "src/main/java/com/tether/app/ui/theme/GeneratedTokens.kt",
    )
    val root: JsonObject by lazy { Json.parseToJsonElement(jsonFile.readText()).jsonObject }
    val skins: JsonObject get() = root.getValue("skins").jsonObject
}
