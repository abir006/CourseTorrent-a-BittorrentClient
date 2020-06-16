@file:Suppress("UNCHECKED_CAST")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.security.MessageDigest
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import java.lang.Exception
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.ceil
import kotlin.math.pow

const val URL_ERROR = -1
const val LOADED_TORRENTS = "Loaded_Torrents"
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
                                        val piecesStorage: Pieces,
                                        val loadedTorrents: LoadedTorrents,
                                        val httpClient: HttpClient) {
    companion object {
        private val md = MessageDigest.getInstance("SHA-1")
        private val studentId = byteArray2Hex(md.digest((204596597 + 311242440).toString().toByteArray())).take(6)
        private val randomGenerated = (1..6).map{(('A'..'Z')+('a'..'z')+('0'..'9')).random()}.joinToString("")
    }
    private val peerId = "-CS1000-$studentId$randomGenerated"
    private val port = "6882"
    private var serverSocket: ServerSocket? = null
    private var activeSockets: HashMap<String, HashMap<KnownPeer, Socket?>> = hashMapOf() // Maps infohash->KnownPeer->Socket
    private val activePeers: HashMap<String, HashMap<KnownPeer, ConnectedPeer>> = hashMapOf() // Maps infohash->KnownPeer->ConnectedPeer (ConnectedPeer has additional properties)
    private val peersBitMap: HashMap<String, HashMap<KnownPeer, HashMap<Long, Byte>>> = hashMapOf() // Maps infohash->KnownPeer->PieceIndex->0/1
    private val peersRequests: HashMap<String, HashMap<KnownPeer, HashMap<Long, ArrayList<Pair<Int, Int>>>>> = hashMapOf() // Maps infohash->KnownPeer->PieceIndex->(offset, length)
    private var pieceHashMap: HashMap<String, HashMap<Long, Piece>> = hashMapOf()
    private var torrentTimer: HashMap<String, LocalTime> = hashMapOf()
    private var torrentStatisticsMap: HashMap<String, TorrentStats> = hashMapOf()
    private var keepAliveTimer = LocalTime.now()
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
        var torrentList = arrayListOf<String>()
        return CompletableFuture.supplyAsync {
            parseTorrent(torrent)
        }.thenCompose { (infohash, metaInfo) ->
            loadedTorrents.read(LOADED_TORRENTS).thenCompose { value ->
                if (null != value) {
                    torrentList = (Bencoder(value).decodeData() as ArrayList<String>)
                    if(torrentList.contains(infohash))
                    throw IllegalStateException("load: infohash was already loaded")
                }
                val announcements = (metaInfo["announce-list"] ?: metaInfo["announce"])
                announcesStorage.write(infohash, Bencoder.encodeStr(announcements).toByteArray())
            }.thenCompose {
                val triple = extractFilesAndPieces(metaInfo)
                val numPieces: Long = triple.first
                val piecesMap = triple.second
                val torrentFiles = triple.third
                //didn't start yet
                if(serverSocket != null){
                    torrentTimer[infohash] = LocalTime.now()
                }
                pieceHashMap[infohash] = piecesMap
                val future0 = CompletableFuture.supplyAsync {  torrentList.add(infohash) }.thenCompose{loadedTorrents.write(LOADED_TORRENTS, Bencoder.encodeStr(torrentList).toByteArray())}
                val future1 =  piecesStorage.write(infohash, Bencoder.encodeStr(piecesMap).toByteArray())
                val future2 = torrentFilesStorage.write(infohash, Bencoder.encodeStr(torrentFiles).toByteArray())
                val future3 = CompletableFuture.completedFuture(Unit).thenCompose { var torrentLength: Long = 0
                    piecesMap.forEach{ entry -> torrentLength += entry.value.length }
                    val stat = TorrentStats(0,0,torrentLength,0,0.0,numPieces,0,
                        Duration.ZERO,
                        Duration.ZERO)
                    torrentStatisticsMap[infohash] = stat
                    torrentStatisticsStorage.write(infohash,Bencoder.encodeStr(stat).toByteArray()) }
                CompletableFuture.allOf(future0,future1,future2,future3).thenApply{ infohash }
            }
        }
    }

    /**
     * Returns the number of pieces, pieces map and torrent files map from the torrent meta-info.
     */
    private fun extractFilesAndPieces(metaInfo: HashMap<*, *>): Triple<Long, HashMap<Long, Piece>, ArrayList<TorrentFile>> {
        val infoRawByes = metaInfo["info"] as ByteArray
        val infoDict = Bencoder(infoRawByes).decodeTorrent() as HashMap<*, *>
        var numPieces: Long = 0
        val piecesMap = hashMapOf<Long, Piece>()
        val pieceLength = infoDict["piece length"] as Long
        val torrentFiles = arrayListOf<TorrentFile>()

        if (infoDict.containsKey("files")) {
            // Multiple files
            val files = infoDict["files"] as List<HashMap<String, Any>>
            var totalLength: Long = 0

            files.forEachIndexed { i, file ->
                val fileName = (file["path"] as List<String>).joinToString("\\")
                val fileLength = (file["length"] as Long)
                // At this stage, totalLength is the current offset before adding fileLength
                val torrentFile = TorrentFile(fileName, i.toLong(), totalLength, fileLength)
                totalLength += fileLength
                torrentFiles.add(torrentFile)
            }
            numPieces = ceil(totalLength / (infoDict["piece length"] as Long).toDouble()).toLong()
            for (i in 0 until numPieces) {
                piecesMap[i] = Piece(
                    i,
                    pieceLength,
                    (infoDict["pieces"] as ByteArray).drop(20 * i.toInt()).take(20).toByteArray(),
                    null
                )
            }
        } else {
            // Single file
            numPieces = ceil((infoDict["length"] as Long) / (infoDict["piece length"] as Long).toDouble()).toLong()
            for (i in 0 until numPieces) {
                piecesMap[i] = Piece(
                    i,
                    pieceLength,
                    (infoDict["pieces"] as ByteArray).drop(20 * i.toInt()).take(20).toByteArray(),
                    null
                )
            }
            torrentFiles.add(TorrentFile(infoDict["name"] as String, 0, 0, infoDict["length"] as Long))
        }
        return Triple(numPieces, piecesMap, torrentFiles)
    }

    /**
     * Remove the torrent identified by [infohash] from the system.
     *
     * This is a *delete* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun unload(infohash: String): CompletableFuture<Unit> {
        return loadedTorrents.read(LOADED_TORRENTS).thenCompose { value ->
            if (value == null) {
                throw IllegalArgumentException("unload: infohash isn't loaded")
            }
            val torrentList = (Bencoder(value).decodeData() as ArrayList<String>)
            if (!torrentList.contains(infohash)) {
                throw IllegalArgumentException("unload: infohash isn't loaded")
            } else {
                torrentList.remove(infohash)
                loadedTorrents.write(LOADED_TORRENTS, Bencoder.encodeStr(torrentList).toByteArray())
            }
        }.thenCompose {
            announces(infohash).thenCompose { announces ->
                val futuresList = arrayListOf<CompletableFuture<Unit>>()
                announces as List<List<String>>
                var future = CompletableFuture.completedFuture(Unit)
                announces.forEach { trackerTier: List<String> ->
                    trackerTier.forEach { tracker ->
                        future = future.thenCompose { (trackerStatisticsStorage.delete(infohash + "_" + tracker)) }
                    }
                }
                futuresList.add(future)
                futuresList.add(peersStorage.delete(infohash))
                futuresList.add(piecesStorage.delete(infohash).thenApply { pieceHashMap.remove(infohash); Unit })
                futuresList.add(torrentFilesStorage.delete(infohash))
                futuresList.add(announcesStorage.delete(infohash))
                futuresList.add(torrentStatisticsStorage.delete(infohash).thenApply { torrentStatisticsMap.remove(infohash); Unit })
                CompletableFuture.allOf(*futuresList.toTypedArray()).thenApply { Unit }
            }
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
        return announces(infohash).thenCompose { announces ->
            if (event == TorrentEvent.STARTED) {
                announcesStorage.shuffleTrackers(infohash, announces).thenApply { announces }
            } else {
                CompletableFuture.completedFuture(announces)
            }
        }.thenCompose { announces ->
            announceAux(infohash,event,announces,0,0,uploaded,downloaded,left)
        }
    }

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
                val response = httpClient.getResponse()
                val responseDict = (Bencoder(response).decodeResponse()) as HashMap<*, *>
                if (responseDict.containsKey("failure reason")) {
                    val reason = responseDict["failure reason"] as String
                    trackerStatisticsStorage.addFailure(infohash, tracker, reason).thenApply { Pair(false, 0) }
                } else {
                    CompletableFuture.allOf(
                        peersStorage.addPeers(infohash, responseDict["peers"] as List<Map<*, *>>?) ,
                        announcesStorage.moveTrackerToHead(infohash, announces, announces[tier], tracker),
                        trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)).thenCompose {
                        CompletableFuture.completedFuture(Pair(true, responseDict.getOrDefault("interval", 0) as Int)) } }
        }.exceptionally { exc ->
            if (exc.cause is java.lang.IllegalArgumentException) {
                throw exc
            }
            Pair(false, URL_ERROR) }
        return future.thenCompose { (isDone: Boolean, interval: Int) ->
            if (isDone) {
                CompletableFuture.completedFuture(interval)
            } else if (interval == URL_ERROR) {
                trackerStatisticsStorage.addFailure(infohash, tracker, "announce: URL connection failed").thenCompose {
                    announceAux(infohash, event, announces, tier, trackerIdx + 1, uploaded, downloaded, left)
                }
            } else {
                announceAux(infohash, event, announces, tier, trackerIdx + 1, uploaded, downloaded, left)
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
                        val response = (Bencoder(httpClient.getResponse()).decodeResponse()) as HashMap<*, *>
                        val responseDict = (response["files"] as HashMap<*, *>).values.first() as HashMap<*, *>
                        trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)
                    } else {
                        // Tracker doesn't support scraping
                        trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
                    }
                }.exceptionally {
                    trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
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
            val stat = torrentStatisticsMap[infohash]
            if (stat == null) {
                (bencoder.decodeData() as TorrentStats)
            } else {
                if(stat.downloaded>0) {
                    stat.shareRatio = stat.uploaded.toDouble() / stat.downloaded
                }
                if (serverSocket == null) {
                    stat
                } else if (stat.left > 0) {
                    stat.leechTime += Duration.between(torrentTimer[infohash], LocalTime.now())
                    stat
                } else {
                    stat.seedTime += Duration.between(torrentTimer[infohash], LocalTime.now())
                    stat
                }
            }
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
         return loadedTorrents.read(LOADED_TORRENTS).thenCompose { infoListBytes ->
            if (null != serverSocket) {
                throw java.lang.IllegalStateException("start: client is already listening on the specified port")
            }
            serverSocket = ServerSocket(port.toInt()) // Socket server to allow peers to connect to courseTorrent
            serverSocket!!.soTimeout = 100
            var future = CompletableFuture.completedFuture(Unit)
            if (infoListBytes != null) {
                val infoList = Bencoder(infoListBytes).decodeData() as ArrayList<String>
                infoList.forEach { infohash ->
                    future = future.thenCompose {
                        torrentTimer[infohash] = LocalTime.now()
                        torrentStatisticsStorage.read(infohash) }.thenCompose { statsBytes ->
                        val stats = Bencoder(statsBytes!!).decodeData() as TorrentStats
                        torrentStatisticsMap[infohash] = stats
                        piecesStorage.read(infohash)
                    }.thenApply { pieceMapBytes ->
                        if (pieceMapBytes != null) {
                            val pieceMap = Bencoder(pieceMapBytes).decodeData() as HashMap<Long, Piece>
                            pieceHashMap[infohash] = pieceMap
                        }
                    }
                }
            }
            future
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
    fun stop(): CompletableFuture<Unit> {
        val stopTime = LocalTime.now()
        return CompletableFuture.supplyAsync {
            activeSockets.forEach { torrentSockets ->
                torrentSockets.value.forEach { socketMap ->
                    if (socketMap.value?.isClosed == false)
                        socketMap.value?.close()
                }
            }
            activeSockets = hashMapOf()
            serverSocket?.close()
            serverSocket = null
        }.thenCompose { loadedTorrents.read(LOADED_TORRENTS) }.thenCompose { infoListBytes ->
            var future = CompletableFuture.completedFuture(Unit)
            if(infoListBytes != null) {
               val infoList = Bencoder(infoListBytes).decodeData() as List<String>
                infoList.forEach{ infohash ->
                    future = future.thenCompose {
                        val stats = torrentStatisticsMap[infohash]
                        if(stats!!.left > 0){
                            stats.leechTime +=  Duration.between(torrentTimer[infohash], stopTime)
                        }else {
                            stats.seedTime += Duration.between(torrentTimer[infohash], stopTime)
                        }
                        torrentStatisticsStorage.write(infohash, Bencoder.encodeStr(stats).toByteArray())
                    }
                }
            }
            future
        }
    }

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
        lateinit var socket: Socket
        return knownPeers(infohash).thenCompose { peersList ->
            if (!peersList.contains(peer)) {
                throw IllegalArgumentException("connect: peer is not known")
            }
            announcesStorage.read(infohash)
        }.thenCompose { value ->
            if (null == value) {
                throw IllegalArgumentException("connect: infohash is not loaded")
            }
            // Open a socket and send a handshake message
            socket = Socket(peer.ip, peer.port)
            socket.soTimeout = 100
            val socketOutputStream = socket.getOutputStream()
            socketOutputStream.write(WireProtocolEncoder.handshake(Bencoder.decodeHexString(infohash)!!, peerId.toByteArray()))
            // Verify handshake response
            val response = socket.getInputStream().readNBytes(68)
            if (WireProtocolDecoder.handshake(response).infohash.contentEquals(Bencoder.decodeHexString(infohash)!!)) {
                val mapSockets = activeSockets[infohash] ?: hashMapOf()
                mapSockets[peer] = socket
                activeSockets[infohash] = mapSockets
                val mapPeers = activePeers[infohash] ?: hashMapOf()
                mapPeers[peer] = ConnectedPeer(peer)
                activePeers[infohash] = mapPeers
                // Init bitfield
                piecesStorage.read(infohash).thenApply { pieceMapBytes ->
                    val pieceMap = Bencoder(pieceMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>
                    val bitField = ByteArray(pieceMap.size)
                    pieceMap.forEach { index, piece ->
                        bitField[index.toInt()] = if (null == piece.data) 0.toByte() else 1.toByte()
                    }
                    if (bitField.contains(1.toByte())) {
                        socketOutputStream.write(WireProtocolEncoder.encode(5.toByte(),bitField))
                    }
                }.thenCompose {
                    sleep(100)
                    handleSmallMessages() }
            } else {
                // activePeers and activeSockets weren't updated yet
                if (!socket.isClosed) {
                    socket.close()
                }
                CompletableFuture.completedFuture(Unit)
            }
        }.exceptionally { exc ->
            if (exc.cause is java.lang.IllegalArgumentException) {
                throw exc
            }
            if (!socket.isClosed) {
                socket.close()
                activeSockets[infohash]?.remove(peer)
                activePeers[infohash]?.remove(peer)
            }
            throw PeerConnectException("connect: connection to peer failed")
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
    fun disconnect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { announce ->
            if (null == announce) {
                throw IllegalArgumentException("disconnect: infohash is not loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("disconnect: peer is not connected")
            }
            val mapSockets = activeSockets[infohash]
            if (null != mapSockets) {
                if (mapSockets[peer]?.isClosed == false) {
                    mapSockets[peer]!!.close()
                }
                mapSockets[peer] = null
                activeSockets[infohash] = mapSockets
            }
            val mapPeers = activePeers[infohash]
            if (null != mapPeers) {
                mapPeers.remove(peer)
                activePeers[infohash] = mapPeers
            }
        }
    }

    /**
     * Return a list of peers that this client is currently connected to, with some statistics.
     *
     * See [ConnectedPeer] for more information.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     */
    fun connectedPeers(infohash: String): CompletableFuture<List<ConnectedPeer>> {
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) throw IllegalArgumentException("connectedPeers: infohash isn't loaded")
            activePeers[infohash]?.values?.toList() ?: listOf()
        }
    }

    /**
     * Send a choke message to [peer], which is currently connected. Future calls to [connectedPeers] should show that
     * this peer is choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun choke(infohash: String, peer: KnownPeer): CompletableFuture<Unit>{
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) {
                throw IllegalArgumentException("choke: infohash isn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("choke: peer is not connected")
            }
            val socket = activeSockets[infohash]!![peer]
            socket!!.getOutputStream().write(WireProtocolEncoder.encode(0.toByte()))
            val connectedPeer = activePeers[infohash]!![peer]!!
            connectedPeer.amChoking = true
            activePeers[infohash]!![peer] = connectedPeer
        }
    }

    /**
     * Send an unchoke message to [peer], which is currently connected. Future calls to [connectedPeers] should show
     * that this peer is not choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) {
                throw IllegalArgumentException("unchoke: infohash isn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("unchoke: peer is not connected")
            }
            val socket = activeSockets[infohash]!![peer]
            socket!!.getOutputStream().write(WireProtocolEncoder.encode(1.toByte()))
            val connectedPeer = activePeers[infohash]!![peer]!!
            connectedPeer.amChoking = false
            activePeers[infohash]!![peer] = connectedPeer
        }
    }

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
    fun handleSmallMessages(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val timeForKeepAlive = Duration.between(keepAliveTimer, LocalTime.now()).toMinutes()
            if (timeForKeepAlive >= 1) {
                keepAliveTimer = LocalTime.now()
            }
            activeSockets.forEach { infoString ->
                val infohash = infoString.key
                infoString.value.forEach { socketMap ->
                    val socket = socketMap.value
                    val peer = socketMap.key
                    try {
                        if (socket != null) {
                            if (timeForKeepAlive >= 1) {
                                socket.getOutputStream().write(ByteArray(4))
                            }
                            while(true) {
                                socket.soTimeout = 100
                                val msgLenBytes = (socket.getInputStream().readNBytes(4))
                                val msgLen = WireProtocolDecoder.length(msgLenBytes)
                                if (msgLen > 0) {
                                    val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                                    val msg = msgLenBytes.plus(restOfMsg)
                                    val msgId = WireProtocolDecoder.messageId(msg)
                                    if (msgId == 0.toByte()) {
                                        // choke
                                        handleChoke(infohash, peer)
                                    } else if (msgId == 1.toByte()) {
                                        // unchoke
                                        handleUnChoke(infohash, peer)
                                    } else if (msgId == 4.toByte()) {
                                        // have
                                        if(handleHave(infohash, peer, msg)){
                                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                                        }
                                    } else if (msgId == 5.toByte()) {
                                        // bitfield
                                        if(handleBitField(infohash, peer, msg)){
                                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                                        }
                                    } else if (msgId == 6.toByte()) {
                                        // request
                                        handleRequest(infohash, peer, msg)
                                    }
                                }
                            }
                        }
                        } catch(e : Exception) {
                        if (e !is SocketTimeoutException) {
                            activeSockets[infohash]!![socketMap.key] = null
                        }
                    }
                }
            }
        }.thenApply {
            serverSocket!!.soTimeout = 100
            while(true) {
                val socket = serverSocket!!.accept()
                socket.soTimeout = 100
                val ip = socket.inetAddress.hostAddress
                val port = socket.port
                val handshake = WireProtocolDecoder.handshake(socket.getInputStream().readNBytes(68))
                // assuming according to assignment that handle does not fail and therefore infohash is valid.
                socket.getOutputStream().write(WireProtocolEncoder.handshake(handshake.infohash, peerId.toByteArray()))
                val peer = KnownPeer(ip,port, String(handshake.peerId))
                val infohash = byteArray2Hex(handshake.infohash)
                val actSockets = activeSockets[infohash] ?: hashMapOf()
                actSockets[peer] = socket
                activeSockets[infohash] = actSockets
                val mapPeers = activePeers[infohash] ?: hashMapOf()
                mapPeers[peer] = ConnectedPeer(peer)
                activePeers[infohash] = mapPeers
            }
        }.exceptionally { /* Finished accepting new sockets. */ }
    }

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
    fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        var downloaded: Long = 0
        return announcesStorage.read(infohash).thenCompose { value ->
            if (null == value) {
                throw IllegalArgumentException("requestPiece: infohash wasn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("requestPiece: peer is not known")
            }
            if (peersBitMap[infohash]?.get(peer)?.get(pieceIndex)?.equals(1.toByte()) != true) {
                throw IllegalArgumentException("requestPiece: peer doesn't have pieceIndex")
            }
            if (activePeers[infohash]!![peer]!!.peerChoking) {
                throw PeerChokedException("requestPiece: peer is choking")
            }
            piecesStorage.read(infohash)
        }.thenCompose { piecesMapBytes ->
            val partLength = 2.0.pow(14).toInt()
            var requestLength = partLength
            val socket = activeSockets[infohash]!![peer]
            val piecesMap = Bencoder(piecesMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>
            val pieceLength = piecesMap[pieceIndex]!!.length.toInt()
            val numParts = ceil(pieceLength / partLength.toDouble()).toInt()
            //if lastPartLength is not 0 then the last piece is smaller than 2^14.
            val lastPartLength = (pieceLength.rem(partLength))
            val requestedPiece =
                Piece(pieceIndex, pieceLength.toLong(), piecesMap[pieceIndex]!!.hashValue, ByteArray(pieceLength))
            for (i in 0 until numParts) {
                if (i == numParts - 1 && lastPartLength != 0) {
                    requestLength = lastPartLength
                }
                socket!!.getOutputStream().write(
                    WireProtocolEncoder.encode(6.toByte(), pieceIndex.toInt(), i * (partLength), requestLength)
                )
                downloaded += requestPieceHandler(socket, infohash, peer, requestedPiece, i, partLength, requestLength)
            }
            val md = MessageDigest.getInstance("SHA-1")
            val pieceHash = md.digest(requestedPiece.data!!)
            if (!requestedPiece.hashValue.contentEquals(pieceHash)) {
                throw PieceHashException("requestPiece: piece is not correct")
            }
            piecesMap[pieceIndex] = requestedPiece
            sendHaveAndNotInterested(infohash, pieceIndex, peer, piecesMap)
            pieceHashMap[infohash] = piecesMap
            torrentStatisticsMap[infohash]!!.downloaded += downloaded
            torrentStatisticsMap[infohash]!!.left -= downloaded
            if(torrentStatisticsMap[infohash]!!.left == 0.toLong()){
                val timeNow = LocalTime.now()
                torrentStatisticsMap[infohash]!!.leechTime += Duration.between(torrentTimer[infohash], timeNow)
                torrentTimer[infohash] = timeNow
            }
            torrentStatisticsMap[infohash]!!.havePieces += 1
            piecesStorage.write(infohash, Bencoder.encodeStr(piecesMap).toByteArray())
        }.exceptionally { exc ->
            if (exc.cause is PeerChokedException || exc.cause is PieceHashException){
                torrentStatisticsMap[infohash]!!.downloaded += downloaded
                torrentStatisticsMap[infohash]!!.wasted += downloaded
                throw exc
            } else if (exc.cause is IllegalArgumentException){
                throw exc
            } else {
                torrentStatisticsMap[infohash]!!.downloaded += downloaded
                torrentStatisticsMap[infohash]!!.wasted += downloaded
                activeSockets[infohash]!!.remove(peer)
                activePeers[infohash]!!.remove(peer)
                throw PeerConnectException("requestPiece: peer disconnected")
            }
        }
    }

    private fun requestPieceHandler(
        socket: Socket,
        infohash: String,
        peer: KnownPeer,
        requestedPiece: Piece,
        i: Int,
        partLength: Int,
        requestLength: Int
    ): Long {
        var downloaded: Long = 0
        try {
            while (true) {
                val msgLenBytes = (socket.getInputStream().readNBytes(4))
                val msgLen = WireProtocolDecoder.length(msgLenBytes)

                if (msgLen > 0) {
                    val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                    val msg = msgLenBytes.plus(restOfMsg)
                    val msgId = WireProtocolDecoder.messageId(msg)
                    if (msgId == 0.toByte()) {
                        // choke
                        handleChoke(infohash, peer)
                        throw PeerChokedException("requestPiece: peer is choking")
                    } else if (msgId == 1.toByte()) {
                        // unchoke
                        handleUnChoke(infohash, peer)
                    } else if (msgId == 4.toByte()) {
                        // have
                        if (handleHave(infohash, peer, msg)) {
                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                        }
                    } else if (msgId == 5.toByte()) {
                        // bitfield
                        if (handleBitField(infohash, peer, msg)) {
                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                        }
                    } else if (msgId == 6.toByte()) {
                        // request
                        handleRequest(infohash, peer, msg)
                    } else if (msgId == 7.toByte()) {
                        WireProtocolDecoder.decode(msg, 2).contents.copyInto(
                            requestedPiece.data!!,
                            i * (partLength)
                        )
                        downloaded += requestLength
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is SocketTimeoutException)
                throw e
        }
        return downloaded
    }

    private fun sendHaveAndNotInterested(
        infohash: String,
        pieceIndex: Long,
        peer: KnownPeer,
        piecesMap: HashMap<Long, Piece>
    ) {
        activeSockets[infohash]!!.forEach { conPeer ->
            if (conPeer.value != null) {
                conPeer.value!!.getOutputStream().write(WireProtocolEncoder.encode(4.toByte(), pieceIndex.toInt()))
                if (activePeers[infohash]!![peer]!!.amInterested) {
                    var sendNotInterested = true
                    val peerBit = peersBitMap[infohash]!![conPeer.key]!!
                    piecesMap.forEach { piece ->
                        if (peerBit[piece.key] == 1.toByte() && null == piece.value.data) {
                            sendNotInterested = false
                        }
                    }
                    if (sendNotInterested) {
                        conPeer.value!!.getOutputStream().write(WireProtocolEncoder.encode(3.toByte()))
                        activePeers[infohash]!![conPeer.key]!!.amInterested = false
                    }
                }
            }
        }
    }

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
    fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenCompose { announces ->
            if (null == announces) {
                throw IllegalArgumentException("sendPiece: infohash isn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("sendPiece: peer is not connected")
            }
            if (peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.isEmpty() != false) {
                throw IllegalArgumentException("sendPiece: peer did not request the specified piece")
            }
            piecesStorage.read(infohash)
        }.thenApply { piecesMapBytes ->
            val piecesMap = Bencoder(piecesMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>
            val socket = activeSockets[infohash]!![peer]!!

            // Deal with existing requests.
            peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.forEach { partPair ->
                sendPiecePart(infohash, peer, socket, pieceIndex, partPair, piecesMap)
            }

            // Socket has 100ms timeout, so we'll just keep reading messages from the peer's socket til we get a timeout.
            socket.soTimeout = 100
            while (true) {
                val msgLenBytes = socket.getInputStream().readNBytes(4)
                val msgLen = WireProtocolDecoder.length(msgLenBytes)

                if (msgLen > 0) {
                    val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                    val msg = msgLenBytes.plus(restOfMsg)
                    val msgId = WireProtocolDecoder.messageId(msg)
                    if (msgId == 0.toByte()) {
                        // choke
                        handleChoke(infohash, peer)
                    } else if (msgId == 1.toByte()) {
                        // unchoke
                        handleUnChoke(infohash, peer)
                    } else if (msgId == 4.toByte()) {
                        // have
                        if(handleHave(infohash, peer, msg)){
                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                        }
                    } else if (msgId == 5.toByte()) {
                        // bitfield
                        if(handleBitField(infohash, peer, msg)){
                            socket.getOutputStream().write(WireProtocolEncoder.encode(2.toByte()))
                        }
                    } else if (msgId == 6.toByte()) {
                        // request
                        handleRequest(infohash, peer, msg)

                        // Deal with pending requests.
                        peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.forEach { partPair ->
                            sendPiecePart(infohash, peer, socket, pieceIndex, partPair, piecesMap)
                        }
                    }
                }
            }
        }.exceptionally { exc ->
            if (exc.cause is IllegalArgumentException) {
                throw exc
            }
        }
    }

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
    fun availablePieces(infohash: String, perPeer: Long, startIndex: Long):
            CompletableFuture<Map<KnownPeer, List<Long>>> {
        return piecesStorage.read(infohash).thenApply { piecesMapBytes ->
            if (null == piecesMapBytes) {
                throw IllegalArgumentException("availablePieces: infohash not loaded")
            }
            val setNeededPieces = hashSetOf<Long>()
            val availablePiecesMap = hashMapOf<KnownPeer, List<Long>>()
            val piecesMap = Bencoder(piecesMapBytes).decodeData() as HashMap<Long, Piece>

            // Leave only the pieces we don't have yet.
            piecesMap.forEach { entry ->
                if (null == entry.value.data) {
                    setNeededPieces.add(entry.key)
                }
            }
            val connectedPeers = activePeers[infohash]?.values ?: listOf<ConnectedPeer>()

            // Leave only the pieces the peer has with index larger than startIndex
            for (peer in connectedPeers.filter { peer -> !peer.peerChoking }) {
                val peerPieces = arrayListOf<Long>()
                for (i in setNeededPieces.filter { x -> x >= startIndex }.sorted()) {
                    if (peersBitMap[infohash]?.get(peer.knownPeer)?.get(i) == 1.toByte()) {
                        peerPieces.add(i)
                    }
                }
                // Leave only the pieces the peer has with index smaller than startIndex
                for (i in setNeededPieces.filter { x -> x < startIndex }.sorted()) {
                    if (peersBitMap[infohash]?.get(peer.knownPeer)?.get(i) == 1.toByte()) {
                        peerPieces.add(i)
                    }
                }
                availablePiecesMap[peer.knownPeer] = peerPieces.take(perPeer.toInt())
            }
            availablePiecesMap
        }
    }

    /**
     * List pieces that have been requested by (unchoked) peers.
     *
     * If a a peer sent us a request message for a subset of a piece (possibly more than one), that piece will be listed
     * here.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of unique pieces that it has requested.
     */
    fun requestedPieces(infohash: String): CompletableFuture<Map<KnownPeer, List<Long>>> {
        return CompletableFuture.supplyAsync {
            val requestedMap = hashMapOf<KnownPeer, List<Long>>()
            peersRequests[infohash]?.forEach { reqMap ->
                val peerReqPieces = arrayListOf<Long>()
                reqMap.value.forEach { peerReqMap ->
                    peerReqPieces.add(peerReqMap.key)
                }
                requestedMap[reqMap.key] = peerReqPieces
            }
            requestedMap
        }
    }

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
    fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> {
        lateinit var piecesMap: HashMap<Long, Piece>
        lateinit var torrentFilesMap: ArrayList<TorrentFile>
        return piecesStorage.read(infohash).thenCompose { piecesMapRaw ->
            if (null == piecesMapRaw) {
                throw java.lang.IllegalArgumentException("files: infohash isn't loaded")
            }
            piecesMap = Bencoder(piecesMapRaw).decodeData() as HashMap<Long, Piece>
            torrentFilesStorage.read(infohash)
        }.thenApply { torrentFilesMapRaw ->
            torrentFilesMap = Bencoder(torrentFilesMapRaw!!).decodeData() as ArrayList<TorrentFile>
            val filesMap = hashMapOf<String, ByteArray>()
            val totalPieces = ByteArray(0)

            // Concatenate the pieces to a single bytearray for easy iteration, sorted by piece index using stream.
            piecesMap.entries.stream()
                .sorted(compareBy { entry: Map.Entry<Long, Piece> -> entry.key })
                .forEach { pieceEntry ->
                    val pieceData = pieceEntry.value.data ?: ByteArray(pieceEntry.value.length.toInt())
                    totalPieces.plus(pieceData)
                }

            // Iterate the torrent files and extract the bytes by order.
            torrentFilesMap.forEach { torrentFile -> // Assuming not null since infohash is loaded.
                val name = torrentFile.name
                val offset = torrentFile.offset.toInt()
                val length = torrentFile.length.toInt()

                filesMap[name] = totalPieces.drop(offset).take(length).toByteArray()
            }
            filesMap
        }
    }

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> {
        lateinit var piecesData: ByteArray
        return torrentFilesStorage.read(infohash).thenCompose { torrentFilesMapRaw ->
            if (null == torrentFilesMapRaw) {
                throw IllegalArgumentException("loadFiles: infohash isn't loaded")
            }
            val torrentFilesMap = Bencoder(torrentFilesMapRaw).decodeData() as ArrayList<TorrentFile>

            // Calculate the total length of the pieces combined
            var totalPiecesLength = 0.toLong()
            torrentFilesMap.forEach { torrentFile -> totalPiecesLength += torrentFile.length }

            // Iterate the files we need and set the given bytes in piecesData by the files indices.
            piecesData = ByteArray(0)
            torrentFilesMap
                .sortedWith( compareBy { torrentFile -> torrentFile.index } )
                .forEach { torrentFile ->
                    val fileName = torrentFile.name
                    val length = torrentFile.length.toInt()
                    var fileData = files[fileName] ?: ByteArray(0) // Empty if not received
                    fileData = fileData.plus(ByteArray(length - fileData.size)) // Pad with zeroes
                    piecesData.plus(fileData) }

            // Extract the different pieces from the concatenated piecesData
            piecesStorage.read(infohash)
        }.thenCompose { piecesMapRaw ->
            val piecesMap = Bencoder(piecesMapRaw!!).decodeData() as HashMap<Long, Piece>
            val updatedPiecesMap = hashMapOf<Long, Piece>()
            piecesMap.forEach { piecePair ->
                val pieceLength = piecePair.value.length
                val pieceIndex = piecePair.value.index
                val pieceHash = piecePair.value.hashValue
                val pieceOffset = pieceIndex * pieceLength
                val pieceData = piecesData.drop(pieceOffset.toInt()).take(pieceLength.toInt()).toByteArray()
                updatedPiecesMap[pieceIndex] = Piece(pieceIndex, pieceLength, pieceHash, pieceData)
            }
            pieceHashMap[infohash] = updatedPiecesMap
            piecesStorage.write(infohash, Bencoder.encodeStr(updatedPiecesMap).toByteArray())
        }
    }

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    fun recheck(infohash: String): CompletableFuture<Boolean> {
        return loadedTorrents.read(LOADED_TORRENTS).thenCompose { infoList ->
            if (infoList == null) {
                throw IllegalArgumentException("recheck: infohash wasnt loaded")
            }
            if (!(Bencoder(infoList).decodeData() as List<String>).contains(infohash)) {
                throw IllegalArgumentException("recheck: infohash wasnt loaded")
            }
            piecesStorage.read(infohash).thenApply { pieceMapBytes ->
                if (pieceMapBytes == null) {
                    false
                } else {
                    val pieceMap = Bencoder(pieceMapBytes).decodeData() as HashMap<Long, Piece>
                    var dataFitsHash = true
                    val md = MessageDigest.getInstance("SHA-1")
                    pieceMap.values.forEach { piece ->
                        if (piece.data == null) {
                            dataFitsHash = false
                        } else if (!(md.digest(piece.data)!!.contentEquals(piece.hashValue))) {
                            dataFitsHash = false
                        }
                    }
                    dataFitsHash
                }
            }
        }
    }




    private fun handleChoke(infohash: String, peer: KnownPeer) {
        activePeers[infohash]!![peer]!!.peerChoking = true
    }

    private fun handleUnChoke(infohash: String, peer: KnownPeer) {
        activePeers[infohash]!![peer]!!.peerChoking = false
    }

    /**
     * returns true if need to send interested as a reply , false otherwise
     */
    private fun handleHave(infohash: String, peer: KnownPeer, msg: ByteArray) : Boolean {
        var sendInderested = false
        val haveMsg = WireProtocolDecoder.decode(msg,1)
        val pieceIndex = haveMsg.ints[0]
        val torrentPeerBitMap = peersBitMap[infohash] ?: hashMapOf()
        val peerBitMap = torrentPeerBitMap[peer] ?: hashMapOf()
        peerBitMap[pieceIndex.toLong()] = 1.toByte()
        torrentPeerBitMap[peer] = peerBitMap
        peersBitMap[infohash] = torrentPeerBitMap
        if(!activePeers[infohash]!![peer]!!.amInterested){
            if(pieceHashMap[infohash]!![pieceIndex.toLong()]!!.data == null){
                sendInderested = true
            }

        }
        return sendInderested
    }

    /**
     * returns true if need to send interested as a reply , false otherwise
     */
    private fun handleBitField(infohash: String, peer: KnownPeer, msg: ByteArray) : Boolean {
        var sendInderested = false
        val bitFieldmsg = WireProtocolDecoder.decode(msg,0)
        val bitfield = bitFieldmsg.contents
        val torrentPeerBitMap = peersBitMap[infohash] ?: hashMapOf()
        val peerBitMap = torrentPeerBitMap[peer] ?: hashMapOf()
        bitfield.forEachIndexed { i, byte ->
            peerBitMap[i.toLong()] = byte
        }
        torrentPeerBitMap[peer] = peerBitMap
        peersBitMap[infohash] = torrentPeerBitMap
        if(!activePeers[infohash]!![peer]!!.amInterested){
            peerBitMap.forEach{ entry ->
                if(entry.value == 1.toByte() && pieceHashMap[infohash]!![entry.key]!!.data == null){
                    sendInderested = true
                }
            }
        }
        return sendInderested
    }

    private fun handleRequest(infohash: String, peer: KnownPeer, msg: ByteArray) {
        val requestMsg = WireProtocolDecoder.decode(msg,3)
        val pieceIndex = requestMsg.ints[0]
        val offset = requestMsg.ints[1]
        val length = requestMsg.ints[2]
        val partLength = 2.0.pow(14).toInt()

        // Get current requests list of the specified piece, peer and infohash
        val torrentPeers = peersRequests[infohash] ?: hashMapOf()
        val peersRequestsMap = torrentPeers[peer] ?: hashMapOf()
        val requestsList = peersRequestsMap[pieceIndex.toLong()] ?: arrayListOf()

        // Update the list
        requestsList.add(Pair(offset, length))
        peersRequestsMap[pieceIndex.toLong()] = requestsList
        torrentPeers[peer] = peersRequestsMap
        peersRequests[infohash] = torrentPeers
    }

    /**
     * Sends a specified part of a wanted piece.
     */
    private fun sendPiecePart(infohash: String, peer: KnownPeer, socket: Socket, pieceIndex: Long, partPair:
    Pair<Int, Int>, piecesMap: HashMap<Long, Piece>) {
        val offset = partPair.first
        val length = partPair.second
        val pieceData = piecesMap[pieceIndex]!!.data
        val partData = pieceData!!.drop(offset).take(length).toByteArray()
        socket.getOutputStream().write(
            WireProtocolEncoder.encode(7.toByte(), partData, pieceIndex.toInt(), offset, length))
        // Remove the partIndex from the requests pieces map.
        torrentStatisticsMap[infohash]!!.uploaded += length
        peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.remove(partPair)
    }
}