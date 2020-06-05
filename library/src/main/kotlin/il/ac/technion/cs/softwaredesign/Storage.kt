package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

/**
 * Synchronizes the cache and the database while serving as a convenient wrapper for I/O. Receives an injected
 * SecureStorage instance, which can be either a simulated storage for testing (DBSimulator) or an actual data base, and
 * managing the i/o requests through the cache and SecureStorage.
 */
open class Storage @Inject constructor(val database: SecureStorage) {
    val cache = Cache()

    /**
     * Adds the pair (key, value) to both the cache and the storage.
     **/
    fun write(key: String, value: ByteArray): CompletableFuture<Unit> {
        val future: CompletableFuture<Unit> = CompletableFuture.supplyAsync {
            cache.write(key, value)
        }.thenComposeAsync {
            database.write(key.toByteArray(), value)
        }
        return future
    }

    /**
     * Returns null if the key was not found, else returns the value associated with the key.
     * First searches the local cache, and then the DB and updates the cache accordingly.
     * ByteArray(0) marks a deleted or not found key so as to prevent unnecessary access to the DB in the future.
     */
    fun read(key: String): CompletableFuture<ByteArray?> {
        val future: CompletableFuture<ByteArray?> = CompletableFuture.supplyAsync {
            cache.read(key)
        }.thenComposeAsync { value:ByteArray? ->
            if (null == value) {
                database.read(key.toByteArray()).thenApplyAsync { dbValue ->
                    // Key not found in DB
                    if (null == dbValue || dbValue.contentEquals(ByteArray(0))) {
                        // Update cache
                        cache.write(key, ByteArray(0))
                        null
                    } else {
                        cache.write(key, dbValue)
                       dbValue
                    }
                }
            } else if (value.contentEquals(ByteArray(0))) {
                // Key deleted
                CompletableFuture<ByteArray?>()
            } else {
                // Key found in cache
                CompletableFuture.completedFuture(value)
            }
        }
        return future
    }

    /**
     * Removes an entry from both the cache and the storage. A deleted value in the database is marked with ByteArray(0)
     */
    fun delete(key: String): CompletableFuture<Unit> {
        val future: CompletableFuture<Unit> = CompletableFuture.supplyAsync {
            cache.delete(key)
        }.thenComposeAsync {
            database.write(key.toByteArray(), ByteArray(0))
        }
        return future
    }
}
