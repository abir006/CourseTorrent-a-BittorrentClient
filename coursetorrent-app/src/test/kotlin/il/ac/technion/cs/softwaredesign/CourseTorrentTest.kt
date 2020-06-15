@file:Suppress("UNCHECKED_CAST")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import com.natpryce.hamkrest.allElements
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.hasSize
import com.natpryce.hamkrest.isA
import dev.misfitlabs.kotlinguice4.getInstance
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import io.mockk.*
import net.bytebuddy.build.Plugin
import org.checkerframework.common.value.qual.StaticallyExecutable
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ExecutionException
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.IOError
import java.lang.Exception
import java.lang.Thread.sleep
import java.net.MalformedURLException
import java.net.ServerSocket
import java.net.Socket
import java.sql.Time
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


class CourseTorrentTest {
    // "CourseTorrentFile" is being used repeatedly to simulate a persistent database using Json for serialization.
    // Under the assumption that the DB is persistent, a restart is being simulated by clearing the cache in between tests.
    val injector = Guice.createInjector(CourseTorrentTestModule())
    var courseTorrent = injector.getInstance<CourseTorrent>()

    @BeforeEach
    // Resets the BitTorent client database to test independently
    fun `init the BitTorrent client database`() {
        mockkObject(courseTorrent.announcesStorage)
        (courseTorrent.announcesStorage.database.get() as DBSimulator).clear()
        (courseTorrent.peersStorage.database.get() as DBSimulator).clear()
        (courseTorrent.trackerStatisticsStorage.database.get() as DBSimulator).clear()
        (courseTorrent.torrentStatisticsStorage.database.get() as DBSimulator).clear()
        (courseTorrent.torrentFilesStorage.database.get() as DBSimulator).clear()
        (courseTorrent.piecesStorage.database.get() as DBSimulator).clear()
        (courseTorrent.loadedTorrents.database.get() as DBSimulator).clear()
    }

    @AfterEach
    fun `stop torrent`() {
        courseTorrent.stop()
    }

    @Disabled
    @Nested
    inner class `homework 0 tests` {
        @Nested
        inner class `testing load functionalities` {
            @Test
            fun `sanity staff test, infohash calculated correctly after load`() {
                val info = courseTorrent.load(debian).get()
                verify(exactly = 1) { courseTorrent.announcesStorage.write(info, debianAnnouncesBytes.toByteArray()) }
                assertThat(info, equalTo(debianInfoHash))
            }

            @Test
            fun `announce list parsed correctly after loading torrent`() {
                val info = courseTorrent.load(kiwiBuntu).get()

                verify(exactly = 1) { courseTorrent.announcesStorage.write(info, kiwiAnnouncesBytes.toByteArray()) }
                assertThat(info, equalTo(kiwiInfoHash))
            }

            @Test
            fun `same torrent loaded twice throws illegal state`() {
                val future = assertDoesNotThrow { courseTorrent.load(kiwiBuntu).thenCompose { courseTorrent.load(kiwiBuntu) } }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<IllegalStateException>())
            }

            @Test
            fun `bad torrent load throws illegal argument`() {
                val future = assertDoesNotThrow { courseTorrent.load("I'm not a torrent".toByteArray()) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<java.lang.IllegalArgumentException>())
            }

            @Test
            fun `multiple loads are loaded in a correct order with correct infohashes and announces (as bytes)`() {
                courseTorrent.load(kiwiBuntu).get()
                courseTorrent.load(debian).get()
                courseTorrent.load(templeOS).get()

                // Given that a write operation first checks the key is valid, there will be a read operation first.
                verifyOrder {
                    courseTorrent.announcesStorage.write(kiwiInfoHash, kiwiAnnouncesBytes.toByteArray())
                    courseTorrent.announcesStorage.write(debianInfoHash, debianAnnouncesBytes.toByteArray())
                    courseTorrent.announcesStorage.write(templeOSInfoHash, templeOSAnnouncesBytes.toByteArray())
                }
            }
        }

        @Nested
        inner class `testing unload functionalities` {
            @Test
            fun `unloading a torrent deletes it from the storage, reading it returns null`() {
                val info = courseTorrent.load(kiwiBuntu).get()
                courseTorrent.unload(info).get()

                verifyOrder {
                    courseTorrent.announcesStorage.write(info, kiwiAnnouncesBytes.toByteArray())
                    courseTorrent.announcesStorage.delete(info)
                }
                assertNull(courseTorrent.announcesStorage.read(info).get())
            }

            @Test
            fun `unloading an unloaded torrent throws an illegal argument`() {
                val future = assertDoesNotThrow { courseTorrent.unload(clementineInfoHash) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<java.lang.IllegalArgumentException>())
            }

            @Test
            fun `unloading a torrent twice throws an illegal argument`() {
                val future = assertDoesNotThrow {
                    courseTorrent.load(templeOS)
                }.thenCompose {
                    courseTorrent.unload(templeOSInfoHash)
                }.thenCompose {
                    courseTorrent.unload(templeOSInfoHash)
                }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<java.lang.IllegalArgumentException>())
            }
        }

        @Nested
        inner class `testing announces` {
            @Test
            fun `announces returns a list of lists for a single announce and for announce-list`() {
                courseTorrent.load(templeOS).thenCompose { // Has a single announce
                courseTorrent.load(kiwiBuntu) }.get() // Has an announce-list
                val templeList = courseTorrent.announces(templeOSInfoHash).get()
                val kiwiList = courseTorrent.announces(kiwiInfoHash).get()

                assertThat(templeList, allElements(hasSize(equalTo(5))))
                assertThat(templeList, hasSize(equalTo(1)))
                assertThat(kiwiList, allElements(hasSize(equalTo(2))))
                assertThat(kiwiList, hasSize(equalTo(1)))
            }

            @Test
            fun `requesting announces of an unloaded torrent throws illegal argument`() {
                val future = assertDoesNotThrow { courseTorrent.announces(templeOSInfoHash) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<java.lang.IllegalArgumentException>())
            }

            @Test
            fun `announces of numerous loaded torrents should return a correct and pre-known list`() {
                courseTorrent.load(kiwiBuntu).thenCompose {
                courseTorrent.load(debian) }.thenCompose {
                courseTorrent.load(templeOS) }.get()

                val kiwiList = courseTorrent.announces(kiwiInfoHash).get()
                val debianList = courseTorrent.announces(debianInfoHash).get()
                val templeOSList = courseTorrent.announces(templeOSInfoHash).get()

                assertThat(kiwiList, equalTo(kiwiAnnounces))
                assertThat(debianList, equalTo(debianAnnounces))
                assertThat(templeOSList, equalTo(templeOSAnnounces))
            }
        }

        @Nested
        inner class `testing persistence` {
            @Test
            fun `loaded torrent is persistent after restart and valid for reading`() {
                courseTorrent.load(kiwiBuntu).get()
                courseTorrent = injector.getInstance<CourseTorrent>()

                assertThat(
                    String(courseTorrent.announcesStorage.read(kiwiInfoHash).get() ?: ByteArray(0)),
                    equalTo(kiwiAnnouncesBytes)
                )
            }

            @Test
            fun `requesting announces for a torrent loaded before restart should work`() {
                courseTorrent.load(kiwiBuntu).get()
                courseTorrent = injector.getInstance<CourseTorrent>()

                assertThat(courseTorrent.announces(kiwiInfoHash).get(), equalTo(kiwiAnnounces))
            }

            @Test
            fun `unloading a torrent loaded before restart should work`() {
                courseTorrent.load(templeOS).get()
                courseTorrent = injector.getInstance<CourseTorrent>()
                courseTorrent.unload(templeOSInfoHash).get()

                assertNull(courseTorrent.announcesStorage.read(templeOSInfoHash).get())
            }
        }
    }

    @Disabled
    @Nested
    inner class `homework 1 tests` {

        @Nested
        inner class `testing announce functionality` {
            @Test
            fun `throws IllegalArgumentException when infohash not loaded on announce`(){
                every { courseTorrent.httpClient.getResponse() } returns bencodedFailureAnnounceResponse.toByteArray()
                val future = assertDoesNotThrow {
                    courseTorrent.announce(debianInfoHash, TorrentEvent.STARTED, 10, 12, 14) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<java.lang.IllegalArgumentException>())
            }

            @Test
            fun `correct interval value after simple announce`() {
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()
                val info = courseTorrent.load(debian).get()

                val interval = courseTorrent.announce(info, TorrentEvent.STARTED, 10, 12, 14).get()

                assertEquals(interval, 62)
            }

            @Test
            fun `correct peers after announce for torrent with single valid tracker`() {
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()
                val info = courseTorrent.load(debian).get()

                courseTorrent.announce(info, TorrentEvent.STARTED, 10, 12, 14).get()

                assert((courseTorrent.peersStorage.read(info).get() as ByteArray).contentEquals(bencodedSimpleAnnouncePeerList.toByteArray()))
            }

            @Test
            fun `correct scrape after announce for torrent with single valid tracker`() {
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()

                courseTorrent.announce(info, TorrentEvent.STARTED, 10, 12, 14).get()

                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + debianAnnounces[0][0]).get() as ByteArray).
                    contentEquals(bencodedSimpleAnnounceScrape.toByteArray()))
            }

            @Test
            fun `throws failure with reason when all the torrent trackers fail announce`() {
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedFailureAnnounceResponse.toByteArray()
                val future = assertDoesNotThrow {
                    courseTorrent.announce(info, TorrentEvent.STARTED, 10, 12, 14) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<TrackerException>())
            }

            @Test
            fun `correct interval value for torrent a tracker that fails and a tracker that succeeds`() {
                val info = courseTorrent.load(kiwiBuntu).get()
                every { courseTorrent.httpClient.getResponse() } returnsMany listOf(
                    bencodedFailureAnnounceResponse.toByteArray(),
                    bencodedSimpleAnnounceResponse.toByteArray())

                val interval = courseTorrent.announce(info, TorrentEvent.STOPPED, 10, 12, 14).get()

                assertEquals(interval,62)
            }

            @Test
            fun `statistic storage correct after first tracker fails and second succeeds`() {
                val info = courseTorrent.load(kiwiBuntu).get()
                every { courseTorrent.httpClient.getResponse() } returnsMany listOf(
                    bencodedFailureAnnounceResponse.toByteArray(),
                    bencodedSimpleAnnounceResponse.toByteArray())

                val res = courseTorrent.announce(info, TorrentEvent.STOPPED, 10, 12, 14).get()

                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + kiwiAnnounces[0][0]).get() as ByteArray)
                    .contentEquals(bencodedFailureScrape.toByteArray()))
                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + kiwiAnnounces[0][1]).get() as ByteArray)
                    .contentEquals(bencodedSimpleAnnounceScrape.toByteArray()))
            }

            @Test
            fun `announce adds failure when url fails`() {
                val info = courseTorrent.load(kiwiBuntu).get()
                every { courseTorrent.httpClient.getResponse() } throws MalformedURLException("url failed") andThen bencodedSimpleAnnounceResponse.toByteArray()

                courseTorrent.announce(info, TorrentEvent.STOPPED, 10, 12, 14).get()

                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + kiwiAnnounces[0][0]).get() as ByteArray)
                    .contentEquals(bencodedURLFailureScrape.toByteArray()))
                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + kiwiAnnounces[0][1]).get() as ByteArray)
                    .contentEquals(bencodedSimpleAnnounceScrape.toByteArray()))
            }
        }


        @Nested
        inner class `testing scrape functionality`{
            @Test
            fun `scraping a torrent with infohash not loaded throws IllegalArgumentException`() {
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleScrapeResponse.toByteArray()
                val future = assertDoesNotThrow { courseTorrent.scrape(debianInfoHash) }
                val throwable = assertThrows<ExecutionException> { future.get() }

                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<IllegalArgumentException>())
            }

            @Test
            fun `scraping a torrent with a single tracker returns a correct ditctionary`() {
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleScrapeResponse.toByteArray()

                courseTorrent.scrape(info).get()

                assert((courseTorrent.trackerStatisticsStorage.read(info + "_" + debianAnnounces[0][0]).get() as ByteArray)
                    .contentEquals(bencodedSimpleScrape.toByteArray()))
            }

            @Test
            fun `scraping a torrent with trackers not supporting scrape fails all`() {
                val info = courseTorrent.load(templeOS).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleScrapeResponse.toByteArray()

                courseTorrent.scrape(info).get()

                for (tracker in templeOSAnnounces.flatten()) { assert(
                    (courseTorrent.trackerStatisticsStorage.read(info + "_" + tracker).get() as ByteArray).
                        contentEquals(bencodedUrlFailScrape.toByteArray()))
                }
            }
        }


        @Nested
        inner class `testing invalidate peer functionality`{
            @Test
            fun `peer loaded from announce is removed after invalidatePeer`(){
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()
                courseTorrent.announce(info,TorrentEvent.STARTED,123,123,123).get()

                courseTorrent.invalidatePeer(info,KnownPeer("102.39.113.100",51413,"matan")).get()
                val peerList = Bencoder(courseTorrent.peersStorage.read(info).get() as ByteArray).decodeData() as List<*>

                assertFalse(peerList.contains(KnownPeer("102.39.113.100",51413,"matan")))
            }

            @Test
            fun `invalidatePeer doesnt remove an invalid peer`(){
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()
                courseTorrent.announce(info,TorrentEvent.STARTED,123,123,123).get()

                assertDoesNotThrow { courseTorrent.invalidatePeer(info,KnownPeer("6.6.6.6",6666,null)).get() }
                val peerList = Bencoder(courseTorrent.peersStorage.read(info).get() as ByteArray).decodeData() as List<*>

                assertEquals(peerList,(arrayListOf(
                    KnownPeer("107.179.196.13",50000,null),
                    KnownPeer("102.39.113.100",51413,"matan"))))
            }
        }


        @Nested
        inner class `testing knownPeers funcionality`{
            @Test
            fun `knownPeers sorts the list correctly on a list containing two peers`() {
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()
                courseTorrent.announce(info,TorrentEvent.STARTED,123,123,123).get()

                val peerList = courseTorrent.knownPeers(info).get()

                assertEquals(peerList, arrayListOf(
                    KnownPeer("102.39.113.100",51413,"matan"),
                    KnownPeer("107.179.196.13",50000,null)))
            }

            @Test
            fun `knownPeers sorts the list correctly on numerous ip's`() {
                val info = courseTorrent.load(debian).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedKnownPeersResponse.toByteArray()
                courseTorrent.announce(info,TorrentEvent.STARTED,123,123,123).get()

                val peerList = courseTorrent.knownPeers(info).get()

                // peerId indicates the wanted order
                assertEquals(peerList, arrayListOf(
                    KnownPeer("1.198.3.93",51413,"1"),
                    KnownPeer( "32.183.93.40", 51413,"2"),
                    KnownPeer("104.30.244.2",51413,"3"),
                    KnownPeer("104.244.4.1", 51413,"4"),
                    KnownPeer("104.244.253.29",51413,"5"),
                    KnownPeer("123.4.245.23",50000,"6")))
            }
        }

        @Nested
        inner class `testing trackerStats functionality`{
            @Test
            fun `successful announce overrides trackersStats who failed at scrape`() {
                val info = courseTorrent.load(templeOS).get()
                every { courseTorrent.httpClient.getResponse() } returns bencodedSimpleAnnounceResponse.toByteArray()

                courseTorrent.scrape(info).get()
                courseTorrent.announce(info,TorrentEvent.STOPPED,123,123,123).get()
                val trackerStats = courseTorrent.trackerStats(info).get()

                assertEquals(trackerStats, mapOf(
                    templeOSAnnounces[0][0] to Scrape(1000, 0, 26, null),
                    templeOSAnnounces[0][1] to Failure("scrape: URL connection failed"),
                    templeOSAnnounces[0][2] to Failure("scrape: URL connection failed"),
                    templeOSAnnounces[0][3] to Failure("scrape: URL connection failed"),
                    templeOSAnnounces[0][4] to Failure("scrape: URL connection failed")))
            }
        }
    }

    @Nested
    inner class `homework 2 tests` {

        @Nested
        inner class `testing connect functionality` {

            @Test
            fun `connect successful to remote client from client side`() {
                courseTorrent.load(debian).get()
                val x = courseTorrent.start().get()

                val server = ServerSocket(6883)
                server.soTimeout = 100

                val testPeer = KnownPeer("127.0.0.1", 6883, "testPeer")
                courseTorrent.peersStorage.addPeers(
                    debianInfoHash, listOf(
                        hashMapOf(
                            "ip" to "127.0.0.1", "port" to 6883, "peer id" to "testPeer"
                        )
                    )
                ).get()

                val remoteSocket = CompletableFuture.supplyAsync {
                    try {
                        val socket = server.accept()
                        socket.getOutputStream().write(
                            WireProtocolEncoder.handshake(
                                Bencoder.decodeHexString(debianInfoHash)!!,
                                Bencoder.decodeHexString(debianInfoHash.reversed())!!
                            )
                        )
                        server.close()
                        socket
                    } catch (e: Exception) {
                        throw PeerConnectException("remote server accept failed")
                    }
                }
                courseTorrent.connect(debianInfoHash, testPeer).get()

                val output = remoteSocket.get().inputStream.readNBytes(68)
                val (otherInfohash, otherPeerId) = StaffWireProtocolDecoder.handshake(output)

                assertTrue(otherInfohash.contentEquals(Bencoder.decodeHexString(debianInfoHash)!!))
                assertEquals(
                    courseTorrent.connectedPeers(debianInfoHash).get(),
                    listOf(ConnectedPeer(testPeer, false, true))
                )
                courseTorrent.stop().get()
            }

            @Test
            fun `connect to a remote client updates connectedPeers`() {
                courseTorrent.load(debian).get()
                courseTorrent.start().get()

                val server = ServerSocket(6883)
                server.soTimeout = 100

                val testPeer = KnownPeer("127.0.0.1", 6883, "testPeer")
                courseTorrent.peersStorage.addPeers(
                    debianInfoHash, listOf(
                        hashMapOf(
                            "ip" to "127.0.0.1", "port" to 6883, "peer id" to "testPeer"
                        )
                    )
                ).get()

                CompletableFuture.runAsync {
                    try {
                        val socket = server.accept()
                        socket.getOutputStream().write(
                            WireProtocolEncoder.handshake(
                                Bencoder.decodeHexString(debianInfoHash)!!,
                                Bencoder.decodeHexString(debianInfoHash.reversed())!!
                            )
                        )
                        server.close()
                    } catch (e: Exception) {
                        throw PeerConnectException("remote server accept failed")
                    }
                }
                courseTorrent.connect(debianInfoHash, testPeer).get()

                assertEquals(courseTorrent.connectedPeers(debianInfoHash).get(),
                    listOf(ConnectedPeer(testPeer, false, true)))
                server.close()
                courseTorrent.stop().get()
            }
        }

        @Nested
        inner class `testing requestPiece functionality` {

            @Test
            fun `checking requestPiece throws piece is not correct after receiving piece not matching`() {
                courseTorrent.load(debian).get()
                courseTorrent.start().get()

                val serverTest = ServerSocket(6883)
                val futureSocket = CompletableFuture.supplyAsync {
                    try {
                        val socket = Socket("127.0.0.1",6882)
                        val testPeer = KnownPeer("127.0.0.1", socket.localPort, "testsPeerWith20Bytes")
                        courseTorrent.peersStorage.addPeers(
                            debianInfoHash, listOf(
                                hashMapOf(
                                    "ip" to "127.0.0.1", "port" to socket.localPort, "peer id" to "testsPeerWith20Bytes"
                                )
                            )
                        ).get()
                        socket.getOutputStream().write(
                            WireProtocolEncoder.handshake(
                                Bencoder.decodeHexString(debianInfoHash)!!,
                                "testsPeerWith20Bytes".toByteArray()
                            )
                        )
                        val bitmap = ByteArray(16)
                        bitmap[0] = 1.toByte()
                        socket.getOutputStream().write(WireProtocolEncoder.encode(5.toByte(),bitmap))
                        socket.getOutputStream().write(WireProtocolEncoder.encode(1.toByte()))
                        Pair(testPeer, socket)
                    } catch (e: Exception) {
                        throw PeerConnectException("remote server accept failed")
                    }
                }

                val testPeer = futureSocket.get().first
                val socket = futureSocket.get().second
                CompletableFuture.runAsync{
                    for(i in 0..16) {
                        val content = ByteArray(16384).map { byte -> i.toByte() }.toByteArray()
                        socket.getInputStream().readNBytes(17)
                        socket.getOutputStream().write(
                            WireProtocolEncoder.encode(7.toByte(), content, 0, i * 16384)
                        )
                    }
                }
                courseTorrent.handleSmallMessages().get()
                courseTorrent.handleSmallMessages().get()
                val future = assertDoesNotThrow { courseTorrent.requestPiece(debianInfoHash,testPeer ,0) }
                val throwable = assertThrows<ExecutionException> { future.get() }
                checkNotNull(throwable.cause)
                assertThat(throwable.cause!!, isA<PieceHashException>())
                serverTest.close()
                courseTorrent.stop().get()
            }
        }

        @Nested
        inner class `testing handleSmallMessages functionality` {

            @Test
            fun `checking requestedPieces`() {
                courseTorrent.load(debian).get()
                courseTorrent.start().get()
                val port = CompletableFuture.supplyAsync {
                    try {
                        val socket = Socket("127.0.0.1", 6882)
                        socket.getOutputStream().write(
                            WireProtocolEncoder.handshake(
                                Bencoder.decodeHexString(debianInfoHash)!!,
                                Bencoder.decodeHexString(debianInfoHash.reversed())!!))
                       sleep(3000)
                        socket.localPort
                    } catch (e: Exception) {
                        throw PeerConnectException("remote server accept failed")
                    }
                }
                courseTorrent.handleSmallMessages().get()

                assertEquals(courseTorrent.connectedPeers(
                    debianInfoHash).get(), listOf(ConnectedPeer(
                    KnownPeer("127.0.0.1", port.get(), String(Bencoder.decodeHexString(debianInfoHash.reversed())!!)))))
                courseTorrent.stop().get()
            }
        }
    }


    companion object {
        val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
        val debianInfoHash = "5a8062c076fa85e8056451c0d9aa04349ae27909"
        val debianAnnouncesBytes = "41:http://bttracker.debian.org:6969/announce"
        val debianAnnounces = listOf(listOf("http://bttracker.debian.org:6969/announce"))

        val kiwiBuntu = this::class.java.getResource("/KiwiBuntu 20.05 alpha-1.torrent").readBytes()
        val kiwiInfoHash = "1178cf63564eeedb89c698e24b03b8f2fb62466c"
        val kiwiAnnouncesBytes = "ll39:http://legittorrents.info:2710/announce37:http://linuxtracker.org:2710/announceee"
        val kiwiAnnounces = listOf(listOf("http://legittorrents.info:2710/announce", "http://linuxtracker.org:2710/announ" +
        "ce"))

        val exo = this::class.java.getResource("/eXo's Retro Learning Pack.torrent").readBytes()
        val exoInfoHash = "b995822c21fc76a632e9d0d080b909376473c946"
        val exoAnnouncesBytes = "ll42:udp://tracker.opentrackr.org:1337/announceel49:udp://tracker.leechers-paradise.org:" +
            "6969/announceel30:udp://9.rarbg.to:2710/announceel30:udp://9.rarbg.me:2710/announceel35:udp://p4p.ar" +
            "enabg.com:1337/announceel37:udp://exodus.desync.com:6969/announceel33:udp://open.stealth.si:80/annou" +
            "nceel38:udp://tracker.cyberia.is:6969/announceel40:udp://tracker.tiny-vps.com:6969/announceel37:udp:" +
            "//tracker.sbsub.com:2710/announceel42:udp://retracker.lanta-net.ru:2710/announceel41:udp://tracker.t" +
            "orrent.eu.org:451/announceel38:udp://tracker.moeking.me:6969/announceel32:udp://explodie.org:6969/an" +
            "nounceel35:udp://bt2.archive.org:6969/announceel35:udp://bt1.archive.org:6969/announceel39:http://tr" +
            "acker.nyap2p.com:8080/announceel38:udp://tracker3.itzmx.com:6961/announceel39:udp://ipv4.tracker.har" +
            "ry.lu:80/announceel39:http://tracker1.itzmx.com:8080/announceee"
        val exoAnnounces = listOf(
            listOf("udp://tracker.opentrackr.org:1337/announce"),
            listOf("udp://tracker.leechers-paradise.org:6969/announce"),
            listOf("udp://9.rarbg.to:2710/announce"),
            listOf("udp://9.rarbg.me:2710/announce"),
            listOf("udp://p4p.arenabg.com:1337/announce"),
            listOf("udp://exodus.desync.com:6969/announce"),
            listOf("udp://open.stealth.si:80/announce"),
            listOf("udp://tracker.cyberia.is:6969/announce"),
            listOf("udp://tracker.tiny-vps.com:6969/announce"),
            listOf("udp://tracker.sbsub.com:2710/announce"),
            listOf("udp://retracker.lanta-net.ru:2710/announce"),
            listOf("udp://tracker.torrent.eu.org:451/announce"),
            listOf("udp://tracker.moeking.me:6969/announce"),
            listOf("udp://explodie.org:6969/announce"),
            listOf("udp://bt2.archive.org:6969/announce"),
            listOf("udp://bt1.archive.org:6969/announce"),
            listOf("http://tracker.nyap2p.com:8080/announce"),
            listOf("udp://tracker3.itzmx.com:6961/announce"),
            listOf("udp://ipv4.tracker.harry.lu:80/announce"),
            listOf("http://tracker1.itzmx.com:8080/announce"))

        val clementine = this::class.java.getResource("/Clementine 1.3.1 Source.torrent").readBytes()
        val clementineInfoHash = "9804290b9d51bf57cb74a108e81790c349316ff3"
        val clementineAnnouncesBytes = "39:http://legittorrents.info:2710/announce"
        val clementineAnnounces = listOf(listOf("http://legittorrents.info:2710/announce"))

        val templeOS = this::class.java.getResource("/TempleOS.torrent").readBytes()
        val templeOSInfoHash = "e26b53ab8026fc37f473499cd12173cff23fc15d"
        val templeOSAnnouncesBytes = "ll40:udp://tracker.leechers-paradise.org:696935:udp://tracker.openbittorrent.com:80" +
            "27:udp://open.demonii.com:133734:udp://tracker.coppersurfer.tk:696928:udp://exodus.desync.com:6969ee"
        val templeOSAnnounces = listOf(listOf("udp://tracker.leechers-paradise.org:6969", "udp://tracker.openbittorrent.c" +
            "om:80", "udp://open.demonii.com:1337", "udp://tracker.coppersurfer.tk:6969", "udp://exodus.desync.co" +
            "m:6969"))

        val bencodedSimpleAnnouncePeerList = Bencoder.encodeStr(arrayListOf(
            KnownPeer("107.179.196.13",50000,null),
            KnownPeer("102.39.113.100",51413,"matan")))
        val bencodedSimpleAnnounceResponse = Bencoder.encodeStr(hashMapOf("peers" to arrayListOf(
            hashMapOf("ip" to "107.179.196.13","port" to 50000),
            hashMapOf("ip" to "102.39.113.100","port" to 51413,"peer id" to "matan")),
            "complete" to 1000, "incomplete" to 26,"interval" to 62))
        val bencodedSimpleAnnounceScrape = Bencoder.encodeStr(Scrape(1000,0,26,null))
        val bencodedFailureAnnounceResponse = Bencoder.encodeStr(hashMapOf("failure reason" to "we need to test it"))
        val bencodedFailureScrape = Bencoder.encodeStr(Failure("we need to test it"))
        val bencodedURLFailureScrape = Bencoder.encodeStr(Failure("announce: URL connection failed"))
        val bencodedSimpleScrapeResponse = Bencoder.encodeStr(hashMapOf("files" to hashMapOf(
            "binaryinfohash" to hashMapOf(
                "downloaded" to 100, "complete" to 200, "incomplete" to 50, "name" to "gal lalouche"))))
        val bencodedSimpleScrape = Bencoder.encodeStr(Scrape(200, 100, 50, "gal lalouche"))
        val bencodedUrlFailScrape = Bencoder.encodeStr(Failure("scrape: URL connection failed"))
        val bencodedKnownPeersResponse = Bencoder.encodeStr(hashMapOf("peers" to arrayListOf(
            hashMapOf("ip" to "123.4.245.23","port" to 50000,"peer id" to "6"),
            hashMapOf("ip" to "104.244.253.29","port" to 51413,"peer id" to "5"),
            hashMapOf("ip" to "1.198.3.93","port" to 51413,"peer id" to "1"),
            hashMapOf("ip" to "32.183.93.40","port" to 51413,"peer id" to "2"),
            hashMapOf("ip" to "104.30.244.2","port" to 51413,"peer id" to "3"),
            hashMapOf("ip" to "104.244.4.1","port" to 51413,"peer id" to "4"))))
    }

    private fun initiateRemotePeerForConnect(infohash: String): Socket {
        val port: Int = 6882

        val sock = assertDoesNotThrow { Socket("10.100.102.5", port) }
        sock.outputStream.write(
            WireProtocolEncoder.handshake(
                Bencoder.decodeHexString(infohash)!!,
                Bencoder.decodeHexString(infohash.reversed())!!
            )
        )
        return sock
    }
}

