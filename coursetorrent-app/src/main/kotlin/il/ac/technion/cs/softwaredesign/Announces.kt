package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import java.util.concurrent.CompletableFuture

/**
 * A wrapper class to storage of the form infohash->announces, that includes additional methods for handling trackers.
 */
class Announces @Inject constructor(@AnnouncesSecureStorage storage: Storage) : Storage(storage.database) {
    /**
     * Receives a infohash and the matching announces, shuffles the trackers in each tier and updates the storage.
     */
     fun shuffleTrackers(infohash: String, announces: List<List<String>>)  : CompletableFuture<Unit> {
        return CompletableFuture.completedFuture {
            announces.forEach{ lst -> lst.shuffled() }
        }.thenCompose {
            write(infohash, Bencoder.encodeStr(announces).toByteArray())
        }
     }

    /**
     * Receives a infohash, a tracker, his containing tier and the entire announces, moves the tracker to the head of
     * his tier, and updates the storage.
     */
    fun moveTrackerToHead(infohash: String,
                           announces:List<List<String>>,
                           announceList:List<String>,
                           tracker:String)  : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            // New nested list
            val newAnnounceList = announceList.toMutableList()
            newAnnounceList.remove(tracker)
            newAnnounceList.add(0, tracker)

            // New wrapper list
            val idx = announces.indexOf(announceList)
            val newAnnounces = announces.toMutableList()
            newAnnounces.remove(newAnnounceList)
            newAnnounces.add(idx, newAnnounceList)
            newAnnounces
        }.thenCompose { newAnnounces ->
            write(infohash, Bencoder.encodeStr(newAnnounces.toList()).toByteArray())
        }

    }

    companion object {
        /**
         * Creates a complete url in order to announce the given tracker of the torrent identified by the infohash, with
         * the desired parameters.
         */
        fun createAnnounceURL(infohash: String, tracker:String, peerId: String, port: String, uploaded: Long,
                                      downloaded: Long, left: Long, event: TorrentEvent) : String {
            val encodedInfohash = hexToURL(infohash)
            val query: String = "info_hash=$encodedInfohash" +
                    "&peer_id=$peerId" +
                    "&port=$port" +
                    "&uploaded=$uploaded" +
                    "&downloaded=$downloaded" +
                    "&left=$left" +
                    "&compact=1" +
                    "&event=$event"
            return "$tracker?$query"
        }
    }
}