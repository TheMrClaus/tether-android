// Pure Kotlin/JVM: the wire types (Wire, AgentEvent, ClientMessage,
// ServerMessage) and the projection model. No Android imports allowed here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
