package il.ac.technion.cs.softwaredesign

import java.util.*

/**
 * Receives a hex string as a ByteArray and returns it as a string.
 */
fun byteArray2Hex(hash: ByteArray): String {
    val formatter = Formatter()
    for (b in hash)
    {
        formatter.format("%02x", b)
    }
    return formatter.toString()
}

/**
 * Receives a string representing hex values and returns it as a byte array.
 */
fun decodeHexString(hexString: String): ByteArray? {
    fun toDigit(hexChar: Char): Int {
        val digit = Character.digit(hexChar, 16)
        require(digit != -1) { "Invalid Hexadecimal Character: $hexChar" }
        return digit
    }

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

/**
 * Receives the hex output of SHA-1 (infohash for our purpose) and encodes it to be a valid substring of a URL.
 */
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
