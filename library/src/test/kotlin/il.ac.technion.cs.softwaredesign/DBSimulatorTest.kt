package il.ac.technion.cs.softwaredesign

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNull

/**
 * The tests verify the wanted actions according to the json format of map, String and ByteArrays.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DBSimulatorTest {

    @Nested inner class `reading from db` {
        @Test
        fun `read operation provides a correct value`() {
            val dbSimulator = DBSimulator("dbReadTestOneEntry")

            assertThat(String(dbSimulator.read("1".toByteArray()) ?: ByteArray(0)), equalTo("a"))
        }

        @Test
        fun `read operation with an invalid key returns null`() {
            val dbSimulator = DBSimulator("dbReadTestOneEntry")

            assertNull(dbSimulator.read("2".toByteArray()))
        }
    }

    @Nested inner class `writing to db` {
        @Test
        fun `write operation updates the database`() {
            val dbSimulator = DBSimulator("dbWriteTestOneEntry")
            dbSimulator.clear()
            dbSimulator.write("1".toByteArray(), "a".toByteArray())

            assertThat(dbSimulator.file.readText(), equalTo("{\"1\":[97]}"))
        }

        @Test
        fun `write operation to an existing key overrides the database`() {
            val dbSimulator = DBSimulator("dbWriteTestOneEntry")
            dbSimulator.clear()
            dbSimulator.write("1".toByteArray(), "a".toByteArray())
            dbSimulator.write("1".toByteArray(), "b".toByteArray())

            assertThat(dbSimulator.file.readText(), equalTo("{\"1\":[98]}"))
        }

        @Test
        fun `write multiple entries updates the database`() {
            val dbSimulator = DBSimulator("dbWriteTestFewEntries")
            dbSimulator.clear()
            for (i: Int in 1..5) {
                dbSimulator.write(i.toString().toByteArray(), ('a' + i -1).toString().toByteArray())
            }

            assertThat(dbSimulator.file.readText(), equalTo("{\"1\":[97],\"2\":[98],\"3\":[99],\"4\":[100],\"5\":[101]}"))
        }
    }

    @Nested inner class `consistency tests` {
        @Test
        fun `read operation is valid after restarting the database`() {
            val dbSimulator = DBSimulator("dbReadTestOneEntry")
            dbSimulator.restart()

            assertThat(String(dbSimulator.read("1".toByteArray()) ?: ByteArray(0)), equalTo("a"))
        }

        @Test
        fun `write operations are valid after restarting the database`() {
            val dbSimulator = DBSimulator("dbRestartTestFewEntries")
            dbSimulator.clear()
            for (i: Int in 1..5) {
                dbSimulator.write(i.toString().toByteArray(), ('a' + i -1).toString().toByteArray())
            }
            dbSimulator.restart()

            assertThat(dbSimulator.file.readText(), equalTo("{\"1\":[97],\"2\":[98],\"3\":[99],\"4\":[100],\"5\":[101]}"))
        }
    }
}
