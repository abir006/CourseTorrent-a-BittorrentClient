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
    val amChoking: Boolean = true,
    val amInterested: Boolean = false,
    val peerChoking: Boolean = true,
    val peerInterested: Boolean = false,
    val completedPercentage: Double = 0.0,
    val averageSpeed: Double = 0.0
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
    val uploaded: Long, /* Total number of bytes downloaded (can be more than the size of the torrent) */
    val downloaded: Long, /* Total number of bytes uploaded (can be more than the size of the torrent) */
    val left: Long, /* Number of bytes left to download to complete the torrent */
    val wasted: Long, /* Number of bytes downloaded then discarded */
    val shareRatio: Double, /* total bytes uploaded / total bytes downloaded */

    val pieces: Long, /* Number of pieces in the torrent. */
    val havePieces: Long, /* Number of pieces we have */

    val leechTime: Duration, /* Amount of time this torrent was loaded, incomplete, and the client was started */
    val seedTime: Duration /* Amount of time this torrent was loaded, complete, and the client was started */
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

