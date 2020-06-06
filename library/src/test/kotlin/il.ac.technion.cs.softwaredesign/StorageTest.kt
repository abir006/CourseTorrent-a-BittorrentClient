package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import dev.misfitlabs.kotlinguice4.getInstance
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNull
import java.util.concurrent.CompletableFuture

class StorageTest {
    // "StorageTestFile" is being used repeatedly to simulate a persistent database using Json for serialization.
    // Under the assumption that the DB is persistent, a restart is being simulated by clearing the cache in between tests.
    val injector = Guice.createInjector(StorageTestModule())
    val storage = injector.getInstance<Storage>()

    @BeforeEach
    fun `initialize mocks and database`() {
        (storage.database.get() as DBSimulator).clear()
        mockkObject(storage.cache)
        mockkObject(storage.database.get())
    }

    @Nested
    inner class `writing to storage` {
        @Test
        fun `write operation writes to the storage`() {
            storage.write("2", "b".toByteArray()).get()

            verify(exactly = 1) {
                storage.cache.write("2", "b".toByteArray()); storage.database.get()
                .write("2".toByteArray(), "b".toByteArray())
            }
        }

        @Test
        fun `write operation to an existing key overrides the database`() {
            storage.write("3", "c".toByteArray()).get()
            storage.write("3", "d".toByteArray()).get()

            verifyOrder {
                storage.cache.write("3", "c".toByteArray()); storage.database.get()
                .write("3".toByteArray(), "c".toByteArray())
                storage.cache.write("3", "d".toByteArray()); storage.database.get()
                .write("3".toByteArray(), "d".toByteArray())
            }
        }
    }

    @Nested
    inner class `reading from storage` {
        @Test
        fun `read operation of cached value doesn't access the database`() {
            storage.write("5", "e".toByteArray()).get()
            storage.read("5").get()

            verify(exactly = 1) { storage.cache.read("5") }
            verify(exactly = 0) { storage.database.get().read("5".toByteArray()) }
        }

        @Test
        fun `read operation of non cached value reads from database simulating restart`() {
            storage.write("6", "f".toByteArray()).get()
            storage.cache.clear()
            storage.read("6").get()

            verify(exactly=1) { storage.cache.read("6") ; storage.database.get().read("6".toByteArray())}
        }

        @Test
        fun `read operation with an invalid key returns null`() {

            assertNull(storage.read("7").get())
            verify(exactly=1) { storage.cache.read("7") ; storage.database.get().read("7".toByteArray())}
        }
    }

    @Nested inner class `deleting from storage` {
        @Test
        fun `read returns null after the key is deleted`() {
            storage.write("8", "h".toByteArray()).get()
            storage.delete("8").get()

            verify(exactly=1) { storage.cache.delete("8") ; storage.database.get().write("8".toByteArray(), ByteArray(0))}
            assertNull(storage.read("8").get())
        }

        @Test
        fun `deleting an invalid key doesn't fail`() {
            storage.delete("9").get()

            verify(exactly=1) { storage.cache.delete("9") }
            assertNull(storage.read("9").get())
        }
    }
}
