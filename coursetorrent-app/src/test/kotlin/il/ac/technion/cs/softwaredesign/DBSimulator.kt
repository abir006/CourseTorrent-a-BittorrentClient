package il.ac.technion.cs.softwaredesign

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Simulating the database by serializing with jsons using GSON library and replacing ByteArray keys with Strings for
 * simplicity.
 */
class DBSimulator @Inject constructor (fileName: String) : SecureStorage {
    private val PATH = "src\\test\\resources\\"
    val storageType = object : TypeToken<LinkedHashMap<String, ByteArray>>(){}.type
    var file = File(PATH + fileName)
    val gson = Gson()
    var db: LinkedHashMap<String, ByteArray> = (gson.fromJson(file.readText(), storageType) ?: LinkedHashMap())

    override fun write(key: ByteArray, value: ByteArray): CompletableFuture<Unit> {
        val future:CompletableFuture<Unit> = CompletableFuture.supplyAsync {
            db = gson.fromJson(file.readText(), storageType)
        }.thenApplyAsync {
            db[String(key)] = value
            file.writeText(gson.toJson(db))
            Unit
        }

        return future
    }

    override fun read(key: ByteArray): CompletableFuture<ByteArray?> {
        val future:CompletableFuture<ByteArray?> = CompletableFuture.supplyAsync {
            db = gson.fromJson(file.readText(), storageType)
        }.thenApplyAsync {
            db[String(key)]
        }
        return future
    }

    fun restart() {
        db = gson.fromJson(file.readText(), storageType)
    }

    fun clear() {
        file.writeText("{}")
        db = gson.fromJson(file.readText(), storageType)
    }
}