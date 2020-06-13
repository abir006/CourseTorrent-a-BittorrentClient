package il.ac.technion.cs.softwaredesign

import java.sql.Time
import java.time.Duration

sealed class ScrapeData

data class Scrape(
    val complete: Int,
    val downloaded: Int,
    val incomplete: Int,
    val name: String?
) : ScrapeData()

data class Failure(
    val reason: String
) : ScrapeData()

data class KnownPeer(
    val ip: String,
    val port: Int,
    val peerId: String?
)

data class ConnectedPeer(
    val knownPeer: KnownPeer,
    var amChoking: Boolean = true,
    var amInterested: Boolean = false,
    var peerChoking: Boolean = true,
    var peerInterested: Boolean = false,
    var completedPercentage: Double = 0.0,
    var averageSpeed: Double = 0.0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConnectedPeer

        if (knownPeer != other.knownPeer) return false

        return true
    }

    override fun hashCode(): Int {
        return knownPeer.hashCode()
    }
}

data class TorrentStats(
    var uploaded: Long, /* Total number of bytes downloaded (can be more than the size of the torrent) */
    var downloaded: Long, /* Total number of bytes uploaded (can be more than the size of the torrent) */
    var left: Long, /* Number of bytes left to download to complete the torrent */
    var wasted: Long, /* Number of bytes downloaded then discarded */
    var shareRatio: Double, /* total bytes uploaded / total bytes downloaded */

    var pieces: Long, /* Number of pieces in the torrent. */
    var havePieces: Long, /* Number of pieces we have */

    var leechTime: Duration, /* Amount of time this torrent was loaded, incomplete, and the client was started */
    var seedTime: Duration /* Amount of time this torrent was loaded, complete, and the client was started */
)

data class TorrentStatsWrapper(
     var torrentStats: TorrentStats,
     var starTime: Time?,
     var stopTime: Time?
)

data class Piece(
    val index: Long,
    val length: Long,
    val hashValue: ByteArray,
    val data: ByteArray?
)

data class TorrentFile(
    val name: String,
    val index: Long,
    val offset: Long,
    val length: Long
)

