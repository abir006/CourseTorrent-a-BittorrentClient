package il.ac.technion.cs.softwaredesign

import java.time.Duration

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