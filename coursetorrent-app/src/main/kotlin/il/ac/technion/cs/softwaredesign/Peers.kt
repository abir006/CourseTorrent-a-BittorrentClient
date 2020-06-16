package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import java.util.ArrayList
import java.util.concurrent.CompletableFuture

/**
 * A wrapper class to storage of the form infohash->List<KnownPeer>. Each known peer is unique.
 */
class Peers @Inject constructor(@PeersSecureStorage  storage: Storage) : Storage(storage.database) {
    /**
     * Receives a infohash and the peers to be added (list of dictionaries) from a tracker response, creates an
     * up-to-date peers list and updates the storage.
     */
     fun addPeers(infohash: String,
                  newPeers: List<Map<*, *>>?) : CompletableFuture<Unit> {
        return read(infohash).thenCompose { existingPeersRaw ->
            var existingPeers: MutableSet<KnownPeer> = mutableSetOf()
            if (null == newPeers) {
                CompletableFuture.completedFuture(Unit)
            } else {
                if (null != existingPeersRaw)
                    existingPeers = (Bencoder.decodeData(existingPeersRaw) as ArrayList<KnownPeer>).toMutableSet()
                for (peer in newPeers) {
                    val peerId: String? = peer["peer id"] as String?
                    //if (peerId.isNullOrEmpty()) peerId = ""
                    existingPeers.add(KnownPeer(peer["ip"] as String, peer["port"] as Int, peerId))
                }
                write(infohash, Bencoder.encodeData(existingPeers.toList()).toByteArray())
            }
        }
    }
}