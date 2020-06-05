package il.ac.technion.cs.softwaredesign

import java.security.MessageDigest
import java.util.*
import kotlin.collections.HashMap
import java.net.InetAddress

// Public functions to be used in the app.

/**
 * Extracts the infohash and announcements out of the torrent metainfo.
 */
fun parseTorrent(torrent: ByteArray): Pair<String, ByteArray> {
    val bencoder = Bencoder(torrent)
    val metaInfo = bencoder.decodeTorrent() as HashMap<*, *>
    val infoRawBytes = metaInfo["info"] as ByteArray
    val md = MessageDigest.getInstance("SHA-1")
    val infoHash = byteArray2Hex(md.digest(infoRawBytes))
    val announcements = (metaInfo["announce-list"] ?: metaInfo["announce"]) as ByteArray
    return Pair(infoHash, announcements)
}

/**
 * Parses the raw bytes of compact peers and extracts the ip addresses and ports.
 */
@ExperimentalUnsignedTypes
fun parsePeers(peerBytes: ByteArray): ArrayList<LinkedHashMap<*,*>> {
    val list:ArrayList<LinkedHashMap<*,*>> = ArrayList()
    val peerUBytes = peerBytes.toUByteArray()
    for (i in peerUBytes.indices step 6) {
        val peerMap:LinkedHashMap<String,Any?> = LinkedHashMap()
        peerMap["peer id"] = null
        peerMap["ip"] = InetAddress.getByAddress(peerBytes.drop(i).take(4).toByteArray()).hostAddress
        peerMap["port"] = (peerUBytes[i + 4].toString().toInt() * 256 + peerUBytes[i + 5].toString().toInt())
        list.add(peerMap)
    }
    return list
}

fun byteArray2Hex(hash: ByteArray): String {
    val formatter = Formatter()
    for (b in hash)
    {
        formatter.format("%02x", b)
    }
    return formatter.toString()
}

fun hexToURL(hexStr: String): String {
    val output = StringBuilder("")

    var i = 0
    while (i < hexStr.length) {
        val str = hexStr.substring(i, i + 2)
        val char = Integer.parseInt(str, 16).toChar()
        if (char in (CharRange('a','z') + CharRange('A','Z') + CharRange('0','9') + setOf('~','_','-','.')))  {
           output.append(char)
        } else {
            output.append("%$str")
        }
        i += 2
    }
    return output.toString()
}

fun ipToInt(ip: String) : Int{
    val spliitedIp = ip.split(".")
    val res = spliitedIp[0].toInt()*(256*256*256) + spliitedIp[1].toInt()*(256*256) + spliitedIp[2].toInt()*256 + spliitedIp[3].toInt()
    return res
}
