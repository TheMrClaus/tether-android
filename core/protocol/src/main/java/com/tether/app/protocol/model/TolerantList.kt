package com.tether.app.protocol.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * A list whose entries that do not fit [element] (or fail [keep]) are DROPPED, never fatal: one bad row must not fail the
 * object that carries it (a snapshot's session, an Overview panel). A value that is not an array decodes as empty.
 * Encodes as a plain list.
 */
open class SkippingListSerializer<T : Any>(
    private val element: KSerializer<T>,
    private val keep: (T) -> Boolean = { true },
) : KSerializer<List<T>> {
    private val list = ListSerializer(element)
    override val descriptor: SerialDescriptor = list.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            try {
                json.json.decodeFromJsonElement(element, item).takeIf(keep)
            } catch (_: IllegalArgumentException) {
                // SerializationException (and its MissingFieldException) extends IllegalArgumentException.
                null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)
}

/** [SkippingListSerializer] of strings (non-string entries dropped). */
object SkippingStringListSerializer : SkippingListSerializer<String>(String.serializer())

/** `SessionProjection.sessionErrors`: rows that do not fit are dropped. */
object SessionErrorListSerializer : SkippingListSerializer<SessionErrorProjection>(SessionErrorProjection.serializer())

/** `OverviewPendingPanel.consoleItems`: an unknown `kind` (a newer server's) or a malformed item is skipped. */
object OverviewConsoleItemListSerializer : SkippingListSerializer<OverviewConsoleItem>(
    OverviewConsoleItem.serializer(),
    keep = { it.kind in OverviewConsoleItem.KINDS },
)
