package il.ac.technion.cs.softwaredesign

import java.io.*
import java.security.MessageDigest
import java.sql.Time
import java.time.Duration
import kotlin.time.milliseconds
import kotlin.time.toDuration
import kotlin.time.toKotlinDuration


//TODO: maybe we want to add details of the returned dicts? from https://wiki.theory.org/index.php/BitTorrentSpecification

// Based on the following Git project - https://gist.github.com/omarmiatello/b09d4ba995c8c0881b0921c0e7aa9bdc
// Changes were made in order to avoid encoding the Pieces value as UTF8.
/**
 * Receives bencoded input as ByteArray and initializes a suitable Bencoder instance. The decoding is done by calling
 * a decode method from the suitable class. decodeTorrent is suitable for decoding a torrent file, while the other
 * methods are our creation for specified use: decodeResponse decodes a response from a tracker, and decodeData decodes
 * the Scrape, Failure and KnownPeer objects.
 * An encodeStr method is available as a companion object to encode any of the above.
 */
class Bencoder(var arr: ByteArray, var i: Int = 0, var keyStart: MutableList<Int> = mutableListOf(), var keyLength: MutableList<Int> = mutableListOf()) {
    /**
     * Receives an integer representing the amount of bencoded chars to parse.
     */
    fun read(count: Int) : String {
        val x = String(arr.drop(i).take(count).toByteArray())
        i += count
        return x
    }

    /**
     * Recursively reads until finding the char c.
     */
    fun readUntil(c: Char) : String {
        val x = String(arr.drop(i).takeWhile {i++; it.toChar() != c }.toByteArray())

        return x
    }

    /**
     * Decodes a bencoded ByteArray torrent metainfo stored in the Bencoder instance.
     * Returns a metainfo dictionary (String -> ByteArray).
     */
    fun decodeTorrent(): Any = when (read(1)[0]) {
        'i' -> (readUntil('e')).toLong()
        'l' -> ArrayList<Any>().apply {
            var obj = decodeTorrent()
            while (obj != Unit) {
                add(obj)
                obj = decodeTorrent()
            }
        }
        'd' -> HashMap<String, Any>().apply {

            var obj = decodeTorrent()
            while (obj != Unit) {

                when {
                    obj as String == "pieces" -> {
                        val length = readUntil(':').toInt()
                        put(obj as String, arr.drop(i).take(length).toByteArray())
                        i += length
                        obj = decodeTorrent()
                    }
                    obj as String == "info" -> {
                        keyStart.add(i)
                        decodeTorrent()
                        keyLength.add(i - keyStart.last())
                        put(obj as String, arr.drop(keyStart.last()).take(keyLength.last()).toByteArray())
                        //put(obj as String, decodeTorrent())
                        keyLength.removeAt(keyLength.size - 1)
                        keyStart.removeAt(keyStart.size - 1)
                        obj = decodeTorrent()
                    }
                    else -> {
                        keyStart.add(i)
                        //decodeTorrent()
                        keyLength.add(i - keyStart.last())
                        //put(obj as String, arr.drop(keyStart.last()).take(keyLength.last()).toByteArray())
                        put(obj as String, decodeTorrent())
                        keyLength.removeAt(keyLength.size - 1)
                        keyStart.removeAt(keyStart.size - 1)
                        obj = decodeTorrent()
                    }
                }
            }
        }
        'e' -> Unit
        in ('0'..'9') -> read((arr[i - 1].toChar() + readUntil(':')).toInt())
        else -> throw IllegalArgumentException("Char: ${arr[i - 1].toChar()}")
    }

    /**
     * Decodes a bencoded ByteArray tracker response stored in the Bencoder instance.
     * Returns a HashMap dictionary (String -> ByteArray).
     */
    fun decodeResponse(): Any = when (read(1)[0]) {
        'i' -> (readUntil('e')).toInt()
        'l' -> ArrayList<Any>().apply {
            var obj = decodeResponse()
            while (obj != Unit) {
                add(obj)
                obj = decodeResponse()
            }
        }
        'd' -> HashMap<String, Any>().apply {

            var obj = decodeResponse()
            while (obj != Unit) {

                when {
                    obj as String == "peers" -> {
                        if (arr[i].toChar() != 'l') {
                            val length = readUntil(':').toInt()
                            put(obj as String, parsePeers(arr.drop(i).take(length).toByteArray()))
                            i += length
                            obj = decodeResponse()
                        } else {
                            put(obj as String, decodeResponse())
                            obj = decodeResponse()
                        }
                    }
                    else -> {
                        put(obj as String,decodeResponse())
                        obj = decodeResponse()
                    }
                }
            }
        }
        'e' -> Unit
        in ('0'..'9') -> read((arr[i - 1].toChar() + readUntil(':')).toInt())
        else -> throw IllegalArgumentException("Char: ${arr[i - 1].toChar()}")
    }

    /**
     * Decodes a bencoded ByteArray data class (Failure, Scrape or KnownPeer) stored in the Bencoder instance.
     * Returns an instance of the encoded data class.
     */
    fun decodeData(): Any? = when (read(1)[0]) {
        'i' -> (readUntil('e')).toLong()
        'l' -> ArrayList<Any?>().apply {
            var obj = this@Bencoder.decodeData()
            while (obj != Unit) {
                add(obj)
                obj = this@Bencoder.decodeData()
            }
        }
        'd' -> HashMap<Any, Any?>().apply {

            var obj = decodeData()
            while (obj != Unit) {
                if(obj is String) {
                    put(obj as String, decodeData())
                }
                if(obj is Long){
                    put(obj as Long, decodeData())
                }
                obj = decodeData()
            }
        }
        's' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            Scrape(
                (tmpMap["complete"] as Long).toInt(),
                (tmpMap["downloaded"] as Long).toInt(),
                (tmpMap["incomplete"] as Long).toInt(),
                tmpMap["name"] as String?)
        }
        'f' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            Failure(tmpMap["failure reason"] as String)
        }
        'k' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            KnownPeer(
                tmpMap["ip"] as String,
                (tmpMap["port"] as Long).toInt(),
                tmpMap["peerId"] as String?)
        }
        't' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            TorrentStats(
                tmpMap["uploaded"] as Long,
                tmpMap["downloaded"] as Long,
                tmpMap["left"] as Long,
                tmpMap["wasted"] as Long,
                (tmpMap["shareRatio"] as String).toDouble(),
                tmpMap["pieces"] as Long,
                tmpMap["havePieces"] as Long,
                Duration.ofMillis((tmpMap["leechTime"] as Long)),
                Duration.ofMillis(tmpMap["seedTime"] as Long)
            )
        }
        'h' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            TorrentFile(
                tmpMap["name"] as String,
                tmpMap["index"] as Long,
                tmpMap["offset"] as Long,
                tmpMap["length"] as Long
            )
        }
        'p' -> {
            val tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            val data = if (tmpMap["data"] != null) (tmpMap["data"] as String).toByteArray() else null
            Piece(
                tmpMap["index"] as Long,
                tmpMap["length"] as Long,
                decodeHexString(tmpMap["hashValue"] as String) ?: ByteArray(0),
                data
            )
        }
        'n' -> null
        'e' -> Unit
        in ('0'..'9') -> read((arr[i - 1].toChar() + readUntil(':')).toInt())
        else -> throw IllegalArgumentException("Char: ${arr[i - 1].toChar()}")
    }

    companion object {
        /**
         * Receives any of the supported formats, including torrents meta-info, tracker responses and data classes,
         * and returns the encoded string.
         */
        fun encodeStr(obj: Any?): String = when (obj) {
            is Int -> "i${obj}e"
            is Long -> "i${obj}e"
            is String -> "${obj.length}:$obj"
            is List<*> -> "l${obj.joinToString("") { encodeStr(it!!) }}e"
            is HashMap<*, *> -> "d${obj.map { encodeStr(it.key!!) + encodeStr(it.value!!) }.joinToString("")}e"
            is Scrape -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["complete"] = obj.complete
                tmpMap["downloaded"] = obj.downloaded
                tmpMap["incomplete"] = obj.incomplete
                tmpMap["name"] = obj.name
                "s${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value) }.joinToString("")}e"
            }
            is KnownPeer -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["ip"] = obj.ip
                tmpMap["port"] = obj.port
                tmpMap["peerId"] = obj.peerId
                "k${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value) }.joinToString("")}e"
            }
            is Failure -> {
                val tmpMap = HashMap<String, Any>()
                tmpMap["failure reason"] = obj.reason
                "f${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value!!) }.joinToString("")}e"
            }
            is TorrentStats -> {
                val tmpMap = HashMap<String, Any>()
                tmpMap["uploaded"] = obj.uploaded
                tmpMap["downloaded"] = obj.downloaded
                tmpMap["left"] = obj.left
                tmpMap["wasted"] = obj.wasted
                tmpMap["shareRatio"] = obj.shareRatio.toString()
                tmpMap["pieces"] = obj.pieces
                tmpMap["havePieces"] = obj.havePieces
                tmpMap["leechTime"] = obj.leechTime.toMillis()
                tmpMap["seedTime"] = obj.seedTime.toMillis()
                "t${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value!!) }.joinToString("")}e"
            }
            is TorrentFile -> {
                val tmpMap = HashMap<String, Any>()
                tmpMap["name"] = obj.name
                tmpMap["index"] = obj.index
                tmpMap["offset"] = obj.offset
                tmpMap["length"] = obj.length
                "h${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value!!) }.joinToString("")}e"
            }
            is Piece -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["index"] = obj.index
                tmpMap["length"] = obj.length
                tmpMap["hashValue"] = byteArray2Hex((obj.hashValue))
                tmpMap["data"] = obj.data?.toString()

                "p${tmpMap.map { encodeStr(it.key!!) + encodeStr(it.value) }.joinToString("")}e"
            }
            else -> {
                if (obj == null) {
                    "n"
                } else {
                    throw IllegalArgumentException()
                }
            }
        }

        private fun toDigit(hexChar: Char): Int {
            val digit = Character.digit(hexChar, 16)
            require(digit != -1) { "Invalid Hexadecimal Character: $hexChar" }
            return digit
        }

        fun decodeHexString(hexString: String): ByteArray? {
            fun hexToByte(hexString: String): Byte {
                val firstDigit = toDigit(hexString[0])
                val secondDigit = toDigit(hexString[1])
                return ((firstDigit shl 4) + secondDigit).toByte()
            }

            require(hexString.length % 2 != 1) { "Invalid hexadecimal String supplied." }
            val bytes = ByteArray(hexString.length / 2)
            run {
                var i = 0
                while (i < hexString.length) {
                    bytes[i / 2] = hexToByte(hexString.substring(i, i + 2))
                    i += 2
                }
            }
            return bytes
        }
    }
}
