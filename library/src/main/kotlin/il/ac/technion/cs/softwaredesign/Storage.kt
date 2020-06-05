package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage

/**
 * Synchronizes the cache and the database while serving as a convenient wrapper for I/O. Receives an injected
 * SecureStorage instance, which can be either a simulated storage for testing (DBSimulator) or an actual data base, and
 * managing the i/o requests through the cache and SecureStorage.
 */
open class Storage @Inject constructor(val database: SecureStorage) {
    val cache = Cache()
    /**
     * Adds the pair (key, value) to both the cache and the storage.
     */
    fun write(key: String, value: ByteArray) {
        cache.write(key,value)
        database.write(key.toByteArray(), value)
    }

    /**
     * Returns null if the key was not found, else returns the value associated with the key.
     * First searches the local cache, and then the DB and updates the cache accordingly.
     * ByteArray(0) marks a deleted or not found key so as to prevent unnecessary access to the DB in the future.
     */
    fun read(key: String): ByteArray? {
        val value: ByteArray? = cache.read(key)

        if (null == value) {
            val dbValue = database.read(key.toByteArray())
            // Key not found in DB
            if (null == dbValue || dbValue.contentEquals(ByteArray(0))) {
                // Update cache
                cache.write(key, ByteArray(0))
                return null
            } else {
                cache.write(key, dbValue)
                return dbValue
            }
        } else if (value.contentEquals(ByteArray(0)))  {
            // Key deleted
            return null
        } else {
            // Key found in cache
            return value
        }
    }

    /**
     * Removes an entry from both the cache and the storage. A deleted value in the database is marked with ByteArray(0)
     */
    fun delete(key: String) {
        cache.delete(key)
        database.write(key.toByteArray(), ByteArray(0))
    }
}