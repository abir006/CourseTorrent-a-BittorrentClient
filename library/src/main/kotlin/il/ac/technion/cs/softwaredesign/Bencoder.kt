package il.ac.technion.cs.softwaredesign

import java.lang.IllegalArgumentException

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
                        //put(obj as String, arr.drop(i).take(length).toByteArray())
                        i += length
                        obj = decodeTorrent()
                    }
                    else -> {
                        keyStart.add(i)
                        decodeTorrent()
                        keyLength.add(i - keyStart.last())
                        put(obj as String, arr.drop(keyStart.last()).take(keyLength.last()).toByteArray())
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
        'i' -> (readUntil('e')).toInt()
        'l' -> ArrayList<Any?>().apply {
            var obj = this@Bencoder.decodeData()
            while (obj != Unit) {
                add(obj)
                obj = this@Bencoder.decodeData()
            }
        }
        's' -> {
            var tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            Scrape(tmpMap["complete"] as Int,
                tmpMap["downloaded"] as Int,
                tmpMap["incomplete"] as Int,
                tmpMap["name"] as String?)
        }
        'f' -> {
            var tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            Failure(tmpMap["failure reason"] as String)
        }
        'k' -> {
            var tmpMap = HashMap<String, Any?>().apply {
                var obj = this@Bencoder.decodeData()
                while (obj != Unit) {
                    put(obj as String, this@Bencoder.decodeData())
                    obj = this@Bencoder.decodeData()
                }
            }
            KnownPeer(tmpMap["ip"] as String,
                tmpMap["port"] as Int,
                tmpMap["peerId"] as String?)
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
            else -> {
                if(obj == null){
                    "n"
                }else {
                    throw IllegalArgumentException()
                }
            }
        }
    }
}
