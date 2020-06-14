package il.ac.technion.cs.softwaredesign

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * This file contains utilities for handling the BitTorrent Wire Protocol. See the associated test for usage examples.
 * You can modify, delete, replace, or do anything else with this file.
 */

object WireProtocolEncoder {
    /**
     * Encode a message of type [messageId] with several int fields.
     */
    fun encode(messageId: Byte, vararg ints: Int): ByteArray = encode(messageId, ByteArray(0), *ints)

    /**
     * Encode a message of type [messageId] with several int fields then a string block of [contents].
     */
    fun encode(messageId: Byte, contents: ByteArray = ByteArray(0), vararg ints: Int): ByteArray {
        val length = 1 + ints.size*4 + contents.size
        val bb = ByteBuffer.allocate(length + 4)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(length)
        bb.put(messageId)
        ints.forEach { bb.putInt(it) }
        contents.forEach { bb.put(it) }
        return bb.array()
    }

    fun handshake(infohash: ByteArray, peerId: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(68)
        bb.put(19)
        bb.put("BitTorrent protocol".toByteArray())
        bb.putLong(0)
        bb.put(infohash)
        bb.put(peerId)
        return bb.array()
    }
}

object WireProtocolDecoder {
    /**
     * Given [message], a valid (maybe partial, at least 4 bytes) bittorrent message, return its stated length.
     *
     * Use this to figure out how much to receive.
     */
    fun length(message: ByteArray): Int = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN).getInt()

    /**
     * Given [message], a valid (full, including length) bittorrent message, return the message ID.
     *
     * Use this to figure out how to parse the message.
     */
    fun messageId(message: ByteArray): Byte = ByteBuffer.wrap(message, 4, 1).get()

    /**
     * Given [message], a valid (full, including length) bittorrent message, and [numOfInts], the number of integer
     * fields in messages of this type, parse it and return a decoded representation.
     */
    fun decode(message: ByteArray, numOfInts: Int): DecodedMessage {
        val bb = ByteBuffer.wrap(message)
        bb.order(ByteOrder.BIG_ENDIAN)
        val length = bb.getInt()
        val contentsLength = length - numOfInts*4 - 1
        val messageId = bb.get()
        val ints = numOfInts.downTo(1).map { bb.getInt() }
        val contents = ByteArray(contentsLength) // This is an allocation
        if (contentsLength != 0) {
            bb.get(contents) // And this is a copy. Ugly and slow.
        }
        return DecodedMessage(length, messageId, ints, contents)
    }

    fun handshake(message: ByteArray): DecodedHandshake {
        val bb = ByteBuffer.wrap(message, 28, 40)
        val infohash = ByteArray(20)
        val peerId = ByteArray(20)
        bb.get(infohash, 0, 20)
        bb.get(peerId, 0, 20)
        return DecodedHandshake(infohash, peerId)
    }
}

data class DecodedHandshake(
    val infohash: ByteArray,
    val peerId: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodedHandshake

        if (!infohash.contentEquals(other.infohash)) return false
        if (!peerId.contentEquals(other.peerId)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = infohash.contentHashCode()
        result = 31 * result + peerId.contentHashCode()
        return result
    }
}

data class DecodedMessage(
    val length: Int,
    val messageId: Byte,
    val ints: List<Int>,
    val contents: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodedMessage

        if (length != other.length) return false
        if (messageId != other.messageId) return false
        if (ints != other.ints) return false
        if (!contents.contentEquals(other.contents)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + messageId
        result = 31 * result + ints.hashCode()
        result = 31 * result + contents.contentHashCode()
        return result
    }
}

//TODO check if this returns the correct msgLength
fun getMsgLength(bytes: ByteArray): Int{
    val bb = ByteBuffer.wrap(bytes)
    bb.order(ByteOrder.BIG_ENDIAN)
    return bb.getInt()
}