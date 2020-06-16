package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

/**
 * Synchronizes the cache and the database while serving as a convenient wrapper for I/O. Receives an injected
 * SecureStorage instance, which can be either a simulated storage for testing (DBSimulator) or an actual data base, and
 * managing the i/o requests through the cache and SecureStorage.
 */
open class Storage @Inject constructor(var database: CompletableFuture<SecureStorage>) {
    val cache = Cache()

    /**
     * Adds the pair (key, value) to both the cache and the storage.
     **/
    fun write(key: String, value: ByteArray): CompletableFuture<Unit> {
        return database.thenCompose { db ->
            cache.write(key, value)
            db.write(key.toByteArray(), value)
        }
    }

    /**
     * Returns null if the key was not found, else returns the value associated with the key.
     * First searches the local cache, and then the DB and updates the cache accordingly.
     * ByteArray(0) marks a deleted or not found key so as to prevent unnecessary access to the DB in the future.
     */
    fun read(key: String): CompletableFuture<ByteArray?> {
        return database.thenCompose { db ->
            val value = cache.read(key)
            if (null == value) {
                db.read(key.toByteArray()).thenApply { dbValue ->
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
                CompletableFuture()
            } else {
                // Key found in cache
                CompletableFuture.completedFuture(value)
            }
        }
    }

    /**
     * Removes an entry from both the cache and the storage. A deleted value in the database is marked with ByteArray(0)
     */
    fun delete(key: String): CompletableFuture<Unit> {
        return database.thenCompose { db ->
            cache.delete(key)
            db.write(key.toByteArray(), ByteArray(0))
        }
    }
}
