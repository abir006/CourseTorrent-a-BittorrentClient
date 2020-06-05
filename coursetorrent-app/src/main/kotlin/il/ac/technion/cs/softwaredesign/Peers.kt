package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import java.util.ArrayList

/**
 * A wrapper class to storage of the form infohash->List<KnownPeer>. Each known peer is unique.
 */
class Peers @Inject constructor(@PeersSecureStorage  storage: Storage) : Storage(storage.database) {
    /**
     * Receives a infohash and the peers to be added (list of dictionaries) from a tracker response, creates an
     * up-to-date peers list and updates the storage.
     */
     fun addPeers(infohash: String,
                  newPeers: List<Map<*, *>>?) {
        val existingPeersRaw = read(infohash)
        var existingPeers: MutableSet<KnownPeer> = mutableSetOf()
        if (null != existingPeersRaw)
            existingPeers = (Bencoder(existingPeersRaw).decodeData() as ArrayList<KnownPeer>).toMutableSet()
        if (null == newPeers) {
            return
        }
        for (peer in newPeers) {
            val peerId: String? = peer["peer id"] as String?
            //if (peerId.isNullOrEmpty()) peerId = ""
            existingPeers.add(KnownPeer(peer["ip"] as String, peer["port"] as Int, peerId))
        }
        write(infohash, Bencoder.encodeStr(existingPeers.toList()).toByteArray())
    }
}