@file:Suppress("UNUSED_PARAMETER")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.security.MessageDigest
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import java.lang.Exception
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture

/**
 * This is the class implementing CourseTorrent, a BitTorrent client.
 *
 * Currently specified:
 * + Parsing torrent metainfo files (".torrent" files)
 * + Communication with trackers (announce, scrape).
 * + Communication with peers (downloading! uploading!)
 */
class CourseTorrent @Inject constructor(val announcesStorage: Announces,
                                        val peersStorage: Peers,
                                        val trackerStatisticsStorage: TrackerStatistics,
                                        val torrentStatisticsStorage: TorrentStatistics,
                                        val torrentFilesStorage: TorrentFiles,
                                        val httpClient: HttpClient) {
    companion object {
        private val md = MessageDigest.getInstance("SHA-1")
        private val studentId = byteArray2Hex(md.digest((204596597 + 311242440).toString().toByteArray())).take(6)
        private val randomGenerated = (1..6).map{(('A'..'Z')+('a'..'z')+('0'..'9')).random()}.joinToString("")
    }

    private val peerId = "-CS1000-$studentId$randomGenerated"
    private val port = "6882" // TODO change? randomize?
    private var serverSocket: ServerSocket? = null
    private val sockets: HashMap<String, HashMap<KnownPeer, Socket>> = hashMapOf() // TODO KnownPeer, since connect receives KnownPeer
    private val peerBitMaps: HashMap<String, HashMap<KnownPeer, ByteArray>> = hashMapOf()
    private var bitmap: HashMap<String, ByteArray?> = hashMapOf()

    /**
     * Load in the torrent metainfo file from [torrent]. The specification for these files can be found here:
     * [Metainfo File Structure](https://wiki.theory.org/index.php/BitTorrentSpecification#Metainfo_File_Structure).
     *
     * After loading a torrent, it will be available in the system, and queries on it will succeed.
     *
     * This is a *create* command.
     *
     * @throws IllegalArgumentException If [torrent] is not a valid metainfo file.
     * @throws IllegalStateException If the infohash of [torrent] is already loaded.
     * @return The infohash of the torrent, i.e., the SHA-1 of the `info` key of [torrent].
     */
    fun load(torrent: ByteArray): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            parseTorrent(torrent)
        }.thenCompose { (infohash, announcements) ->
            announcesStorage.read(infohash).thenApply { value ->
                if (null != value) {
                    throw IllegalStateException("load: infohash was already loaded")
                }
                announcesStorage.write(infohash, announcements)
                infohash
            }
        }
    }

    /**
     * Remove the torrent identified by [infohash] from the system.
     *
     * This is a *delete* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    // TODO should remove entries from Statistics/Peers?
    fun unload(infohash: String): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenCompose { value ->
            if (null == value) {
                throw java.lang.IllegalArgumentException("unload: infohash wasn't loaded")
            }
            announcesStorage.delete(infohash)
        }
    }

    /**
     * Return the announce URLs for the loaded torrent identified by [infohash].
     *
     * See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more information. This method behaves as follows:
     * * If the "announce-list" key exists, it will be used as the source for announce URLs.
     * * If "announce-list" does not exist, "announce" will be used, and the URL it contains will be in tier 1.
     * * The announce URLs should *not* be shuffled.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Tier lists of announce URLs.
     */
    @Suppress("UNCHECKED_CAST")
    fun announces(infohash: String): CompletableFuture<List<List<String>>> {
        return announcesStorage.read(infohash).thenApply { announcesRaw ->
            if (null == announcesRaw) throw IllegalArgumentException("announces: infohash wasn't loaded")
            val bencoder = Bencoder(announcesRaw)
            bencoder.decodeData()
        }.thenCompose { announces ->
            if (announces is String) {
                CompletableFuture.completedFuture(listOf(listOf(announces)))
            } else {
                CompletableFuture.completedFuture(announces as List<List<String>>)
            }
        }
    }

    /**
     * Send an "announce" HTTP request to a single tracker of the torrent identified by [infohash], and update the
     * internal state according to the response. The specification for these requests can be found here:
     * [Tracker Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_HTTP.2FHTTPS_Protocol).
     *
     * If [event] is [TorrentEvent.STARTED], shuffle the announce-list before selecting a tracker (future calls to
     * [announces] should return the shuffled list). See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more
     * information on shuffling and selecting a tracker.
     *
     * [event], [uploaded], [downloaded], and [left] should be included in the tracker request.
     *
     * The "compact" parameter in the request should be set to "1", and the implementation should support both compact
     * and non-compact peer lists.
     *
     * Peer ID should be set to "-CS1000-{Student ID}{Random numbers}", where {Student ID} is the first 6 characters
     * from the hex-encoded SHA-1 hash of the student's ID numbers (i.e., `hex(sha1(student1id + student2id))`), and
     * {Random numbers} are 6 random characters in the range [0-9a-zA-Z] generated at instance creation.
     *
     * If the connection to the tracker failed or the tracker returned a failure reason, the next tracker in the list
     * will be contacted and the announce-list will be updated as per
     * [BEP 12](http://bittorrent.org/beps/bep_0012.html).
     * If the final tracker in the announce-list has failed, then a [TrackerException] will be thrown.
     *
     * This is an *update* command.
     *
     * @throws TrackerException If the tracker returned a "failure reason". The failure reason will be the exception
     * message.
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return The interval in seconds that the client should wait before announcing again.
     */
    fun announce(infohash: String, event: TorrentEvent, uploaded: Long, downloaded: Long, left: Long): CompletableFuture<Int> {
        return announces(infohash).thenApply { announces ->
            if (event == TorrentEvent.STARTED) {
                announcesStorage.shuffleTrackers(infohash, announces)
            }
            announces
        }.thenCompose { announces ->
            announceAux(infohash,event,announces,0,0,uploaded,downloaded,left)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun announceAux(infohash: String, event: TorrentEvent, announces: List<List<String>>, tier:Int, trackerIdx:Int,
                            uploaded: Long, downloaded: Long, left: Long ): CompletableFuture<Int> {
        if (tier >= announces.size)
            throw TrackerException("announce: last tracker failed")
        if (trackerIdx >= announces[tier].size)
            return announceAux(infohash, event, announces, tier + 1, 0, uploaded, downloaded, left)
        val tracker = announces[tier][trackerIdx]
        val future = CompletableFuture.completedFuture(0).thenCompose {
            val url = Announces.createAnnounceURL(infohash, tracker, peerId, port, uploaded, downloaded, left, event)
            httpClient.setURL(url)
            try {
                val response = httpClient.getResponse()
                val responseDict = (Bencoder(response).decodeResponse()) as HashMap<*, *>
                if (responseDict.containsKey("failure reason")) {
                    val reason = responseDict["failure reason"] as String
                    trackerStatisticsStorage.addFailure(infohash, tracker, reason).thenApply { Pair(false, 0) }
                } else {
                    peersStorage.addPeers(infohash, responseDict["peers"] as List<Map<*, *>>?).thenCompose {
                        announcesStorage.moveTrackerToHead(infohash, announces, announces[tier], tracker)
                    }.thenCompose {
                        trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)
                    }.thenCompose {
                        CompletableFuture.completedFuture(Pair(true, responseDict.getOrDefault("interval", 0) as Int))
                    }
                }
            } catch(e: Exception) {
                trackerStatisticsStorage.addFailure(infohash, tracker, "announce: URL connection failed").thenApply { Pair(false, 0) }
            }
        }
        return future.thenCompose { (isDone: Boolean, interval: Int) ->
            if (isDone) {
                CompletableFuture.completedFuture(interval)
            } else {
                announceAux(infohash, event, announces, tier, trackerIdx+1, uploaded, downloaded, left)
            }
        }
    }

    /**
     * Scrape all trackers identified by a torrent, and store the statistics provided. The specification for the scrape
     * request can be found here:
     * [Scrape Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_.27scrape.27_Convention).
     *
     * All known trackers for the torrent will be scraped.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun scrape(infohash: String): CompletableFuture<Unit> {
        return announces(infohash).thenCompose { announces ->
            var future = CompletableFuture.completedFuture(Unit)
            for (tracker: String in announces.flatten()) {
                future = future.thenCompose {
                    val supportsScraping = tracker.substring(tracker.lastIndexOf('/')).startsWith("/announce")
                    if (supportsScraping) {
                        val url = TrackerStatistics.createScrapeURL(infohash, tracker)
                        httpClient.setURL(url)
                        try {
                            val response = (Bencoder(httpClient.getResponse()).decodeResponse()) as HashMap<*, *>
                            val responseDict = (response["files"] as HashMap<*, *>).values.first() as HashMap<*, *>
                            trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)
                        } catch (e: Exception) {
                            trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
                        }
                    } else {
                        // Tracker doesn't support scraping
                        trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
                    }
                }
            }
            future
        }
    }

    /**
     * Invalidate a previously known peer for this torrent.
     *
     * If [peer] is not a known  peer for this torrent, do nothing.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    @Suppress("UNCHECKED_CAST")
    fun invalidatePeer(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) throw IllegalArgumentException("invalidePeer: infohash wasn't loaded")
        }.thenCompose {
            peersStorage.read(infohash)
        }.thenCompose { peersList ->
            if (null == peersList) {
                CompletableFuture.completedFuture(Unit)
            } else {
                val updatedPeersList = (Bencoder(peersList).decodeData()) as ArrayList<KnownPeer>
                if (updatedPeersList.contains(peer)) {
                    updatedPeersList.remove(peer)
                    peersStorage.write(infohash, Bencoder.encodeStr(updatedPeersList).toByteArray())
                } else {
                    CompletableFuture.completedFuture(Unit)
                }
            }
        }
    }

    /**
     * Return all known peers for the torrent identified by [infohash], in sorted order. This list should contain all
     * the peers that the client can attempt to connect to, in ascending numerical order. Note that this is not the
     * lexicographical ordering of the string representation of the IP addresses: i.e., "127.0.0.2" should come before
     * "127.0.0.100".
     *
     * The list contains unique peers, and does not include peers that have been invalidated.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Sorted list of known peers.
     */
    @Suppress("UNCHECKED_CAST")
    fun knownPeers(infohash: String): CompletableFuture<List<KnownPeer>> {
        return announcesStorage.read(infohash).thenCompose { value ->
            if (null == value) throw IllegalArgumentException("announces: infohash wasn't loaded")
            peersStorage.read(infohash)
        }.thenCompose { peerList ->
            if (null == peerList) {
                CompletableFuture.completedFuture(listOf())
            } else {
                val knownPeersList = Bencoder(peerList).decodeData() as ArrayList<KnownPeer>
                knownPeersList.sortBy{ ipToInt(it.ip) }
                CompletableFuture.completedFuture(knownPeersList.toList())
            }
        }
    }

    /**
     * Return all known statistics from trackers of the torrent identified by [infohash]. The statistics displayed
     * represent the latest information seen from a tracker.
     *
     * The statistics are updated by [announce] and [scrape] calls. If a response from a tracker was never seen, it
     * will not be included in the result. If one of the values of [ScrapeData] was not included in any tracker response
     * (e.g., "downloaded"), it would be set to 0 (but if there was a previous result that did include that value, the
     * previous result would be shown).
     *
     * If the last response from the tracker was a failure, the failure reason would be returned ([ScrapeData] is
     * defined to allow for this). If the failure was a failed connection to the tracker, the reason should be set to
     * "Connection failed".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return A mapping from tracker announce URL to statistics.
     */
    fun trackerStats(infohash: String): CompletableFuture<Map<String, ScrapeData>> {
        return announces(infohash).thenCompose { trackers ->
            var future = CompletableFuture.completedFuture(hashMapOf<String, ScrapeData>())
            for (tracker in trackers.flatten()) {
                future = future.thenCompose { statsMap ->
                    trackerStatisticsStorage.read(infohash + "_" + tracker).thenApply { scrapeData ->
                        if (null != scrapeData)
                            statsMap[tracker] = Bencoder(scrapeData).decodeData() as ScrapeData
                        statsMap
                    }
                }
            }
            future.thenApply { map -> map.toMap() }
        }
    }

    /**
     * Return information about the torrent identified by [infohash]. These statistics represent the current state
     * of the client at the time of querying.
     *
     * See [TorrentStats] for more information about the required data.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Torrent statistics.
     */
    fun torrentStats(infohash: String): CompletableFuture<TorrentStats> {
        return torrentStatisticsStorage.read(infohash).thenApply { torrentStatsRaw ->
            if (null == torrentStatsRaw) throw IllegalArgumentException("torrentStats: infohash wasn't loaded")
            val bencoder = Bencoder(torrentStatsRaw)
            bencoder.decodeData() as TorrentStats
        }
    }

    /**
     * Start listening for peer connections on a chosen port.
     *
     * The port chosen should be in the range 6881-6889, inclusive. Assume all ports in that range are free.
     *
     * For a given instance of [CourseTorrent], the port sent to the tracker in [announce] and the port chosen here
     * should be the same.
     *
     * This is a *update* command. (maybe)
     *
     * @throws IllegalStateException If already listening.
     */
    fun start(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            if (null != serverSocket) {
                throw java.lang.IllegalStateException("start: client is already listening on the specified port")
            }
            serverSocket = ServerSocket(port.toInt()) // Socket server to allow peers to connect to courseTorrent
        }
    }

    /**
     * Disconnect from all connected peers, and stop listening for new peer connections
     *
     * You may assume that this method is called before the instance is destroyed, and perform clean-up here.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalStateException If not listening.
     */
    fun stop(): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Connect to [peer] using the peer protocol described in [BEP 003](http://bittorrent.org/beps/bep_0003.html).
     * Only connections over TCP are supported. If connecting to the peer failed, an exception is thrown.
     *
     * After connecting, send a handshake message, and receive and process the peer's handshake message. The peer's
     * handshake will contain a "peer_id", and future calls to [knownPeers] should return this peer_id for this peer.
     *
     * If this torrent has anything downloaded, send a bitfield message.
     *
     * Wait 100ms, and in that time handle any bitfield or have messages that are received.
     *
     * In the handshake, the "reserved" field should be set to 0 and the peer_id should be the same as the one that was
     * sent to the tracker.
     *
     * [peer] is equal to (and has the same [hashCode]) an object that was returned by [knownPeers] for [infohash].
     *
     * After a successful connection, this peer should be listed by [connectedPeers]. Peer connections start as choked
     * and not interested for both this client and the peer.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not known.
     * @throws PeerConnectException if the connection to [peer] failed (timeout, connection closed after handshake, etc.)
     */
    fun connect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return knownPeers(infohash).thenCompose { peersList ->
            if (!peersList.contains(peer)) {
                throw IllegalArgumentException("connect: peer is not known")
            }
            announcesStorage.read(infohash)
        }.thenCompose { value ->
            if (null == value) {
                throw IllegalArgumentException("connect: infohash is not loaded")
            }
            try {
                val socket = Socket(peer.ip, peer.port)
                val socketOutputStream = socket.getOutputStream()
                //TODO: infohash to byte array or infohash hex2bytearray and peerId also ?
                socketOutputStream.write(WireProtocolEncoder.handshake(infohash.toByteArray(), peerId.toByteArray()))
                val response = socket.getInputStream().readAllBytes()
                if (WireProtocolDecoder.handshake(response).infohash.contentEquals(infohash.toByteArray())) {
                    //TODO: how to get bitmap out of message \ create message with bit map
                    val map = sockets[infohash] ?: hashMapOf()
                    map[peer] = socket
                    sockets[infohash] = map
                }
            } catch (e: Exception) {
                throw PeerConnectException("connect: connection to peer failed")
            }
            CompletableFuture.completedFuture(Unit)
        }
    }

    /**
     * Disconnect from [peer] by closing the connection.
     *ז
     * There is no need to send any messages.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun disconnect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Return a list of peers that this client is currently connected to, with some statistics.
     *
     * See [ConnectedPeer] for more information.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     */
    fun connectedPeers(infohash: String): CompletableFuture<List<ConnectedPeer>> = TODO("Implement me!")

    /**
     * Send a choke message to [peer], which is currently connected. Future calls to [connectedPeers] should show that
     * this peer is choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun choke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Send an unchoke message to [peer], which is currently connected. Future calls to [connectedPeers] should show
     * that this peer is not choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Handle any messages that peers have sent, and send keep-alives if needed, as well as interested/not interested
     * messages.
     *
     * Messages to receive and handle from peers:
     *
     * 1. keep-alive: Do nothing.
     * 2. unchoke: Mark this peer as not choking in future calls to [connectedPeers].
     * 3. choke: Mark this peer as choking in future calls to [connectedPeers].
     * 4. have: Update the internal state of which pieces this client has, as seen in future calls to [availablePieces]
     * and [connectedPeers].
     * 5. request: Mark the peer as requesting a piece, as seen in future calls to [requestedPieces]. Ignore if the peer
     * is choked.
     * 6. handshake: When a new peer connects and performs a handshake, future calls to [knownPeers] and
     * [connectedPeers] should return it.
     *
     * Messages to send to each peer:
     *
     * 1. keep-alive: If it has been more than one minute since we sent a keep-alive message (it is OK to keep a global
     * count)
     * 2. interested: If the peer has a piece we don't, and we're currently not interested, send this message and mark
     * the client as interested in future calls to [connectedPeers].
     * 3. not interested: If the peer does not have any pieces we don't, and we're currently interested, send this
     * message and mark the client as not interested in future calls to [connectedPeers].
     *
     * These messages can also be handled by different parts of the code, as desired. In that case this method can do
     * less, or even nothing. It is guaranteed that this method will be called reasonably often.
     *
     * This is an *update* command. (maybe)
     */
    fun handleSmallMessages(): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Download piece number [pieceIndex] of the torrent identified by [infohash].
     *
     * Attempt to download a complete piece by sending a series of request messages and receiving piece messages in
     * response. This method finishes successfully (i.e., the [CompletableFuture] is completed) once an entire piece has
     * been received, or an error.
     *
     * Requests should be of piece subsets of length 16KB (2^14 bytes). If only a part of the piece is downloaded, an
     * exception is thrown. It is unspecified whether partial downloads are kept between two calls to requestPiece:
     * i.e., on failure, you can either keep the partially downloaded data or discard it.
     *
     * After a complete piece has been downloaded, its SHA-1 hash will be compared to the appropriate SHA-1 has from the
     * torrent meta-info file (see 'pieces' in the 'info' dictionary), and in case of a mis-match an exception is
     * thrown and the downloaded data is discarded.
     *
     * This is an *update* command.
     *
     * @throws PeerChokedException if the peer choked the client before a complete piece has been downloaded.
     * @throws PeerConnectException if the peer disconnected before a complete piece has been downloaded.
     * @throws PieceHashException if the piece SHA-1 hash does not match the hash from the meta-info file.
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] does not have [pieceIndex].
     */
    fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> =
        TODO("Implement me!")

    /**
     * Send piece number [pieceIndex] of the [infohash] torrent to [peer].
     *
     * Upload a complete piece (as much as possible) by sending a series of piece messages. This method finishes
     * successfully (i.e., the [CompletableFuture] is completed) if [peer] hasn't requested another subset of the piece
     * in 100ms.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] did not request [pieceIndex].
     */
    fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * List pieces that are currently available for download immediately.
     *
     * That is, pieces that:
     * 1. We don't have yet,
     * 2. A peer we're connected to does have,
     * 3. That peer is not choking us.
     *
     * Returns a mapping from connected, unchoking, interesting peer to a list of maximum length [perPeer] of pieces
     * that meet the above criteria. The lists may overlap (contain the same piece indices). The pieces in the list
     * should begin at [startIndex] and continue sequentially in a cyclical manner up to `[startIndex]-1`.
     *
     * For example, there are 3 pieces, we don't have any of them, and we are connected to PeerA that has piece 1 and
     * 2 and is not choking us. So, `availablePieces(infohash, 3, 2) => {PeerA: [2, 1]}`.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of [perPeer] pieces that can be downloaded from it, starting at [startIndex].
     */
    fun availablePieces(
        infohash: String,
        perPeer: Long,
        startIndex: Long
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

    /**
     * List pieces that have been requested by (unchoked) peers.
     *
     * If a a peer sent us a request message for a subset of a piece (possibly more than one), that piece will be listed
     * here.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of unique pieces that it has requested.
     */
    fun requestedPieces(
        infohash: String
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

    /**
     * Return the downloaded files for torrent [infohash].
     *
     * Partially downloaded files are allowed. Bytes that haven't been downloaded yet are zeroed.
     * File names are given including path separators, e.g., "foo/bar/file.txt".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from file name to file contents.
     */
    fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> = TODO("Implement me!")

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    fun recheck(infohash: String): CompletableFuture<Boolean> = TODO("Implement me!")
}