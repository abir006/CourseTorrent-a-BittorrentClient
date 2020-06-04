package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.InvalidArgumentException
import com.xenomachina.argparser.mainBody
import dev.misfitlabs.kotlinguice4.getInstance
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

val MAX_CONNECTIONS = 20
val CONNECT_PER_LOOP = 5
val MAX_UNCHOKED = 5

class Args(parser: ArgParser) {
    val torrentFile by parser.positional("TORRENT", help = "Torrent file") { File(this) }
        .addValidator {
            if (!value.exists() || !value.isFile)
                throw InvalidArgumentException("Torrent file must exist and be a file.")
        }
    val outputDir by parser.positional("OUTPUT", help = "Output directory") { File(this) }
        .addValidator { if (value.exists()) throw InvalidArgumentException("Output directory must not exist.") }
}

data class Data(
    val infohash: String,
    val nextAnnounce: LocalDateTime,
    val done: Boolean = false
)

fun main(args: Array<String>): Unit = mainBody("CourseTorrent") {
    ArgParser(args).parseInto(::Args).run {
        println("Welcome to CourseTorrent!")
        val courseTorrent = Guice.createInjector(CourseTorrentModule()).getInstance<CourseTorrent>()
        lateinit var infohash: String // Annoying mutability because captured variables :(
        courseTorrent.load(torrentFile.readBytes()).thenCompose { _infohash ->
            infohash = _infohash
            courseTorrent.start()
        }.thenCompose {
            initialAnnounce(courseTorrent, infohash)
        }.thenAccept { data ->
            mainLoop(courseTorrent, data)
        }.thenCompose {
            completedAnnounce(courseTorrent, infohash)
        }.thenCompose {
            courseTorrent.files(infohash).thenApply { output ->
                output.keys.forEach { path ->
                    outputDir.mkdirs()
                    val file = File(outputDir, path)
                    file.parentFile.mkdirs()
                    file.createNewFile()
                    file.writeBytes(output.getValue(path))
                }
            }
        }.thenCompose {
            stoppedAnnounce(courseTorrent, infohash)
        }.thenCompose {
            disconnectPeers(courseTorrent, infohash)
        }.thenCompose {
            courseTorrent.stop()
        }.get() // Exactly 1 call to get(), at the end of main :)
    }
}

private fun mainLoop(courseTorrent: CourseTorrent, data: Data): CompletableFuture<Void> =
    connectToPeers(courseTorrent, data).thenCompose {
        courseTorrent.handleSmallMessages()
    }.thenCompose {
        unchokePeers(courseTorrent, data)
    }.thenCompose {
        downloadPiece(courseTorrent, data)
    }.thenCompose {
        uploadPiece(courseTorrent, data)
    }.thenCompose {
        regularAnnounce(courseTorrent, data)
    }.thenAccept { newData -> // "recursion" is done using thenAccept so as to not kill the stack.
        if (!newData.done) {
            Thread.sleep(50L) // Sleep a tiny bit before iterating, since we're doing this in a silly manner.
            mainLoop(courseTorrent, newData)
        }
    }

private fun initialAnnounce(
    courseTorrent: CourseTorrent,
    infohash: String
): CompletableFuture<Data> {
    return courseTorrent.torrentStats(infohash)
        .thenCompose {
            courseTorrent.announce(
                infohash,
                TorrentEvent.STARTED,
                uploaded = it.uploaded,
                downloaded = it.downloaded,
                left = it.left
            )
        }.thenApply { interval -> Data(infohash, LocalDateTime.now().plusSeconds(interval.toLong())) }
}

private fun completedAnnounce(
    courseTorrent: CourseTorrent,
    infohash: String
): CompletableFuture<Data> {
    return courseTorrent.torrentStats(infohash)
        .thenCompose {
            courseTorrent.announce(
                infohash,
                TorrentEvent.COMPLETED,
                uploaded = it.uploaded,
                downloaded = it.downloaded,
                left = it.left
            )
        }.thenApply { interval -> Data(infohash, LocalDateTime.now().plusSeconds(interval.toLong())) }
}

private fun stoppedAnnounce(
    courseTorrent: CourseTorrent,
    infohash: String
): CompletableFuture<Data> {
    return courseTorrent.torrentStats(infohash)
        .thenCompose {
            courseTorrent.announce(
                infohash,
                TorrentEvent.STOPPED,
                uploaded = it.uploaded,
                downloaded = it.downloaded,
                left = it.left
            )
        }.thenApply { interval -> Data(infohash, LocalDateTime.now().plusSeconds(interval.toLong())) }
}

private fun regularAnnounce(
    courseTorrent: CourseTorrent,
    data: Data
): CompletableFuture<Data> {
    return courseTorrent.torrentStats(data.infohash).thenCompose {
        if (data.nextAnnounce < LocalDateTime.now()) {
            courseTorrent.announce(
                data.infohash, TorrentEvent.REGULAR,
                uploaded = it.uploaded,
                downloaded = it.downloaded,
                left = it.left
            ).thenApply { interval ->
                Data(
                    data.infohash,
                    LocalDateTime.now().plusSeconds(interval.toLong()),
                    it.left == 0L
                )
            }
        } else {
            CompletableFuture.completedFuture(data)
        }
    }
}

private fun uploadPiece(
    courseTorrent: CourseTorrent,
    data: Data
): CompletableFuture<Unit> {
    return courseTorrent.requestedPieces(data.infohash).thenCompose {
        if (it.isNotEmpty()) {
            val peer = it.keys.shuffled().first()
            val piece = it.getValue(peer).shuffled().first()
            courseTorrent.sendPiece(data.infohash, peer, piece).thenCompose {
                /* after sending the piece, choke the peer */
                courseTorrent.choke(data.infohash, peer)
            }
        } else {
            CompletableFuture.completedFuture(Unit)
        }
    }
}

private fun downloadPiece(
    courseTorrent: CourseTorrent,
    data: Data
): CompletableFuture<Unit> {
    return courseTorrent.torrentStats(data.infohash).thenApply(TorrentStats::pieces).thenCompose { pieces ->
        courseTorrent.availablePieces(data.infohash, 1, Random.nextLong(0, pieces))
    }.thenCompose {
        if (it.isNotEmpty()) {
            val peer = it.keys.asIterable().shuffled().first()
            val piece = it.getValue(peer).shuffled().first()
            courseTorrent.requestPiece(data.infohash, peer, piece)
        } else {
            CompletableFuture.completedFuture(Unit)
        }
    }
}

private fun unchokePeers(
    courseTorrent: CourseTorrent,
    data: Data
): CompletableFuture<Unit> {
    return courseTorrent.connectedPeers(data.infohash).thenApply { ps ->
            val unchoked = ps.count { p -> !p.amChoking }
            ps.asSequence().filter { p -> p.amInterested && p.peerInterested && p.amChoking }
                .map(ConnectedPeer::knownPeer)
                .take(MAX_UNCHOKED - unchoked).asIterable()
        }
        .thenCompose {
            it.fold(CompletableFuture.completedFuture(Unit)) { future, peer ->
                future.thenCompose { courseTorrent.unchoke(data.infohash, peer) }
            }
        }
}

private fun connectToPeers(
    courseTorrent: CourseTorrent,
    data: Data
): CompletableFuture<Unit> {
    return courseTorrent.connectedPeers(data.infohash).thenCompose {
        /* Get a list of peers to connect to (if we aren't connected to enough) */
        val connected = it.asSequence().map(ConnectedPeer::knownPeer).toSet()
        courseTorrent.knownPeers(data.infohash)
            .thenApply { knownPeers ->
                knownPeers.asSequence().filter(connected::contains).take(MAX_CONNECTIONS - connected.size)
                    .take(CONNECT_PER_LOOP).asIterable()
            }
            .thenCompose { peersToConnect ->
                /* Connect to peers (and invalidate those that we can't connect to so that they won't be tried again) */
                peersToConnect.fold(CompletableFuture.completedFuture(Unit)) { future, peer ->
                    future.thenCompose {
                        courseTorrent.connect(data.infohash, peer).handle { _, throwable -> throwable != null }
                            .thenCompose { failedToConnect ->
                                if (failedToConnect)
                                    courseTorrent.invalidatePeer(data.infohash, peer)
                                else
                                    CompletableFuture.completedFuture(Unit)
                            }
                    }
                }
            }
    }
}

private fun disconnectPeers(
    courseTorrent: CourseTorrent,
    infohash: String
): CompletableFuture<CompletableFuture<Unit>> {
    return courseTorrent.connectedPeers(infohash).thenApply { peers ->
        peers.asSequence().map(ConnectedPeer::knownPeer)
            .fold(CompletableFuture.completedFuture(Unit)) { future, peer ->
                future.thenCompose { courseTorrent.disconnect(infohash, peer) }
            }
    }
}