package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import dev.misfitlabs.kotlinguice4.getInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.Socket
import java.util.concurrent.CompletionException

class CourseTorrentStaffTest {
    private val injector = Guice.createInjector(CourseTorrentModule())
    private val torrent = injector.getInstance<CourseTorrent>()
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val lame = this::class.java.getResource("/lame.torrent").readBytes()
    private val lameExe = this::class.java.getResource("/lame.exe")
    private val lameEnc = this::class.java.getResource("/lame_enc.dll")

    @Test
    fun `after load, infohash calculated correctly`() {
        val infohash = torrent.load(debian).get()

        assertThat(infohash, equalTo("5a8062c076fa85e8056451c0d9aa04349ae27909"))
    }

    @Test
    fun `after load, announce is correct`() {
        val infohash = torrent.load(debian).get()

        val announces = assertDoesNotThrow { torrent.announces(infohash).join() }

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(1)))
        assertThat(announces, allElements(hasElement("http://bttracker.debian.org:6969/announce")))
    }

    @Test
    fun `client announces to tracker`() {
        val infohash = torrent.load(lame).get()

        /* interval is 360 */
        val interval = torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()

        assertThat(interval, equalTo(360))
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `client scrapes tracker and updates statistics`() {
        val infohash = torrent.load(lame).get()

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash).join() }

        assertThat(
            torrent.trackerStats(infohash).get(),
            equalTo(mapOf(Pair("https://127.0.0.1:8082/announce", Scrape(0, 0, 0, null) as ScrapeData)))
        )
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `after announce, client has up-to-date peer list`() {
        val infohash = torrent.load(lame).get()

        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440)

        val knownPeers = torrent.knownPeers(infohash).join()

        assertThat(
            knownPeers,
            anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887)))
        )
        assertThat(
            knownPeers,
            anyElement(has(KnownPeer::ip, equalTo("127.0.0.21")) and has(KnownPeer::port, equalTo(6889)))
        )
        assertThat(knownPeers, equalTo(knownPeers.distinct()))
    }

    @Test
    fun `peers are invalidated correctly`() {
        val infohash = torrent.load(lame).get()
        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440).get()

        torrent.invalidatePeer(infohash, KnownPeer("127.0.0.22", 6887, null)).get()

        val knownPeers = torrent.knownPeers(infohash).get()

        assertThat(
            knownPeers,
            anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887))).not()
        )
    }

    @Test
    fun `exceptions are thrown inside the CompletableFuture`() {
        val future = assertDoesNotThrow { torrent.knownPeers("this is not a valid infohash") }
        val throwable = assertThrows<CompletionException> { future.join() }

        checkNotNull(throwable.cause)
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `lame torrent is loaded, file data is loaded, and recheck returns true`() {
        val infohash = torrent.load(lame).get()

        val done = torrent.loadFiles(
                infohash,
                mapOf("lame.exe" to lameExe.readBytes(), "lame_enc.dll" to lameEnc.readBytes())
            )
            .thenCompose { torrent.recheck(infohash) }.get()

        assertTrue(done)
    }

    @Test
    fun `lame torrent is loaded, wrong file data is loaded, and recheck returns false`() {
        val infohash = torrent.load(lame).get()

        val done = torrent.loadFiles(
                infohash,
                mapOf("lame.exe" to "wrong data".toByteArray(), "lame_enc.dll" to "wrongest data".toByteArray())
            )
            .thenCompose { torrent.recheck(infohash) }.get()

        assertFalse(done)
    }

    @Test
    fun `starts listening and responds to connection and handshake`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeer(infohash)

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `lists remotely connected peer in known and connected peers`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeer(infohash)

        val knownPeers = torrent.knownPeers(infohash).get()
        val connectedPeers = torrent.connectedPeers(infohash).get()

        assertThat(connectedPeers.size, equalTo(1))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `sends choke command to peer`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)

        torrent.connectedPeers(infohash).thenApply {
            it.asSequence().map(ConnectedPeer::knownPeer).first() }
            .thenAccept { torrent.choke(infohash, it) }

        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)

        assertThat(message.messageId, equalTo(0.toByte()))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `sends unchoke command to peer`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)

        torrent.connectedPeers(infohash).thenApply {
                it.asSequence().map(ConnectedPeer::knownPeer).first() }
            .thenAccept { torrent.unchoke(infohash, it) }

        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)

        assertThat(message.messageId, equalTo(1.toByte()))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `after receiving have message, a piece is marked as available`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)
        sock.outputStream.write(StaffWireProtocolEncoder.encode(4, 0))
        sock.outputStream.flush()

        val pieces = assertDoesNotThrow {
            torrent.handleSmallMessages().get()
            torrent.availablePieces(infohash, 10, 0).get()
        }

        assertThat(pieces.keys, hasSize(equalTo(1)))
        assertThat(pieces.values.first(), hasElement(0L))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `sends interested message to peer after receiving a have message`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)
        sock.outputStream.write(StaffWireProtocolEncoder.encode(4, 0))
        sock.outputStream.flush()

        assertDoesNotThrow { torrent.handleSmallMessages().get() }

        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)

        assertThat(message.messageId, equalTo(2.toByte()))

        torrent.stop().get()
        sock.close()
    }

    private fun initiateRemotePeer(infohash: String): Socket {
        torrent.torrentStats(infohash).thenCompose {
            torrent.announce(
                infohash,
                TorrentEvent.STARTED,
                uploaded = it.uploaded,
                downloaded = it.downloaded,
                left = it.left
            )
        }.join()

        val port: Int = TODO("Get port from announce")

        assertDoesNotThrow { torrent.start().join() }

        val sock = assertDoesNotThrow { Socket("127.0.0.1", port) }
        sock.outputStream.write(
            WireProtocolEncoder.handshake(
                hexStringToByteArray(infohash),
                hexStringToByteArray(infohash.reversed())
            )
        )

        assertDoesNotThrow { torrent.handleSmallMessages().join() }

        val output = sock.inputStream.readNBytes(68)

        val (otherInfohash, otherPeerId) = StaffWireProtocolDecoder.handshake(output)

        assertTrue(otherInfohash.contentEquals(hexStringToByteArray(infohash)))

        return sock
    }
}

fun hexStringToByteArray(input: String) = input.chunked(2).map { it.toUpperCase().toInt(16).toByte() }.toByteArray()