package il.ac.technion.cs.softwaredesign

import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashMap

/**
 * Based on the following Git project - https://gist.github.com/omarmiatello/b09d4ba995c8c0881b0921c0e7aa9bdc
 * Changes were made in order to avoid encoding the Pieces value as UTF8.
 * The purpose of this class is to provide bencoding based serialization and deserialization for the entire project. It
 * is used either for parsing torrent meta-info files, and for serializing and deserializing our objects in order to
 * keep them persistent. Any new object type which is desired to be serialized can be easily added by modifying the
 * private decodeData method and encodeStr method.
 * The usage of this class is done through the companion object.
 * Decoding is done by calling one of the decode methods below:
 * decodeTorrent - decodes a torrent meta-info file.
 *      Example usage: val metaInfoDict: HashMap<String, Any> = Bencoder.decodeTorrent(torrentFile as ByteArray).
 * decodeResponse - decodes a response from a tracker (while announcing).
 *      Example usage: val responseDict: HashMap<String, Any> = Bencoder.decodeResponse(responseRaw as ByteArray).
 * decodeData - decodes any necessary object for our usage. Supported objects:
 *      Collections - Lists, HashMaps.
 *      Primitive types - Int, Long, String.
 *      Data Classes - Scrape, Failure, KnownPeer, TorrentStats, TorrentFile, Piece.
 *      Example usage: val data: Any = Bencoder.decodeData(obj as ByteArray).
 * Encoding is done by calling the provided method in the companion object:
 * encodeData - encodes any of the supported formats.
 *      Example usage: val scrape: Scrape = encodeData(scrapeRaw).
 */
class Bencoder(var arr: ByteArray, var i: Int = 0, var keyStart: MutableList<Int> = mutableListOf(), var keyLength: MutableList<Int> = mutableListOf()) {

    companion object {
        /**
         * Decodes a bencoded ByteArray torrent metainfo stored.
         * Usage example: val (dict, infohash) = Bencoder().decodeTorrent(debian as ByteArray).
         * Returns a metainfo dictionary (String -> Any) and the infohash.
         */
        fun decodeTorrent(torrent: ByteArray): Pair<String, HashMap<*, *>> {
            val metaInfo = Bencoder(torrent).decodeTorrent() as HashMap<String, Any>
            val infoRawBytes = metaInfo["info"] as ByteArray
            val md = MessageDigest.getInstance("SHA-1")
            val infohash = byteArray2Hex(md.digest(infoRawBytes))

            val infoDict = Bencoder(infoRawBytes).decodeTorrent() as HashMap<*, *>
            metaInfo.remove("info")
            metaInfo["info"] = infoDict

            return Pair(infohash, metaInfo)
        }

        /**
         * Decodes a bencoded ByteArray tracker response.
         * Returns a HashMap dictionary (String -> ByteArray).
         */
        fun decodeTrackerResponse(trackerResponse: ByteArray): HashMap<*, *> {
            return Bencoder(trackerResponse).decodeTrackerResponse() as HashMap<*, *>
        }

        /**
         * Decodes a bencoded ByteArray object and returns an instance of the represented object.
         */
        fun decodeData(dataObj: ByteArray): Any? {
            return Bencoder(dataObj).decodeData()
        }

        /**
         * Receives any of the supported formats, including torrents meta-info, tracker responses and data classes,
         * and returns the encoded string.
         */
        fun encodeData(obj: Any?): String = when (obj) {
            is Int -> "i${obj}e"
            is Long -> "i${obj}e"
            is String -> "${obj.length}:$obj"
            is List<*> -> "l${obj.joinToString("") { encodeData(it!!) }}e"
            is HashMap<*, *> -> "d${obj.map { encodeData(it.key!!) + encodeData(it.value!!) }.joinToString("")}e"
            is Scrape -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["complete"] = obj.complete
                tmpMap["downloaded"] = obj.downloaded
                tmpMap["incomplete"] = obj.incomplete
                tmpMap["name"] = obj.name
                "s${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
            }
            is KnownPeer -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["ip"] = obj.ip
                tmpMap["port"] = obj.port
                tmpMap["peerId"] = obj.peerId
                "k${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
            }
            is Failure -> {
                val tmpMap = HashMap<String, Any>()
                tmpMap["failure reason"] = obj.reason
                "f${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
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
                "t${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
            }
            is TorrentFile -> {
                val tmpMap = HashMap<String, Any>()
                tmpMap["name"] = obj.name
                tmpMap["index"] = obj.index
                tmpMap["offset"] = obj.offset
                tmpMap["length"] = obj.length
                "h${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
            }
            is Piece -> {
                val tmpMap = HashMap<String, Any?>()
                tmpMap["index"] = obj.index
                tmpMap["length"] = obj.length
                tmpMap["hashValue"] = byteArray2Hex((obj.hashValue))
                tmpMap["data"] = obj.data?.toString()

                "p${tmpMap.map { encodeData(it.key) + encodeData(it.value) }.joinToString("")}e"
            }
            else -> {
                if (obj == null) {
                    "n"
                } else {
                    throw IllegalArgumentException()
                }
            }
        }
    }

    /**
     * Receives an integer representing the amount of bencoded chars to parse.
     */
    private fun read(count: Int) : String {
        val x = String(arr.drop(i).take(count).toByteArray())
        i += count
        return x
    }

    /**
     * Recursively reads until finding the char c.
     */
    private fun readUntil(c: Char) : String {
        val x = String(arr.drop(i).takeWhile {i++; it.toChar() != c }.toByteArray())

        return x
    }

    /**
     * Parses the raw bytes of compact peers and extracts the ip addresses and ports.
     */
    @ExperimentalUnsignedTypes
    private fun parsePeers(peerBytes: ByteArray): java.util.ArrayList<LinkedHashMap<*, *>> {
        val list: java.util.ArrayList<LinkedHashMap<*, *>> = java.util.ArrayList()
        val peerUBytes = peerBytes.toUByteArray()
        for (i in peerUBytes.indices step 6) {
            val peerMap: LinkedHashMap<String, Any?> = LinkedHashMap()
            peerMap["peer id"] = null
            peerMap["ip"] = InetAddress.getByAddress(peerBytes.drop(i).take(4).toByteArray()).hostAddress
            peerMap["port"] = (peerUBytes[i + 4].toString().toInt() * 256 + peerUBytes[i + 5].toString().toInt())
            list.add(peerMap)
        }
        return list
    }

    private fun decodeTorrent(): Any = when (read(1)[0]) {
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

    @ExperimentalUnsignedTypes
    private fun decodeTrackerResponse(): Any = when (read(1)[0]) {
        'i' -> (readUntil('e')).toInt()
        'l' -> ArrayList<Any>().apply {
            var obj = decodeTrackerResponse()
            while (obj != Unit) {
                add(obj)
                obj = decodeTrackerResponse()
            }
        }
        'd' -> HashMap<String, Any>().apply {

            var obj = decodeTrackerResponse()
            while (obj != Unit) {

                when {
                    obj as String == "peers" -> {
                        if (arr[i].toChar() != 'l') {
                            val length = readUntil(':').toInt()
                            put(obj as String, parsePeers(arr.drop(i).take(length).toByteArray()))
                            i += length
                            obj = decodeTrackerResponse()
                        } else {
                            put(obj as String, decodeTrackerResponse())
                            obj = decodeTrackerResponse()
                        }
                    }
                    else -> {
                        put(obj as String, decodeTrackerResponse())
                        obj = decodeTrackerResponse()
                    }
                }
            }
        }
        'e' -> Unit
        in ('0'..'9') -> read((arr[i - 1].toChar() + readUntil(':')).toInt())
        else -> throw IllegalArgumentException("Char: ${arr[i - 1].toChar()}")
    }

    private fun decodeData(): Any? = when (read(1)[0]) {
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
}
