package il.ac.technion.cs.softwaredesign

/**
 * Creates a cache in order to store recently used values. Mainly used in order to increase the read/write speeds when
 * accessing already known torrents.
 */
class Cache {
    val cacheMap: LinkedHashMap<String, ByteArray> = LinkedHashMap()

    fun write(key: String, value: ByteArray) {
        cacheMap[key] = value
    }

    fun read(key: String): ByteArray? {
        val switch: ByteArray? = cacheMap[key]
        return if (null == switch || switch.contentEquals(ByteArray(0))) null
        else switch
    }

    fun delete(key: String) {
        // ByteArray(0) marks a deleted value, and thus it's presence in the cache prevents access to the DB.
        cacheMap[key] = ByteArray(0)
    }

    fun clear() {
        cacheMap.clear()
    }
}