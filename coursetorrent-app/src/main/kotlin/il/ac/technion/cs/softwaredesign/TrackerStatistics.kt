package il.ac.technion.cs.softwaredesign


import com.google.inject.Inject
import java.util.HashMap
import java.util.concurrent.CompletableFuture

/**
 * A wrapper class to storage of the form infohash_tracker->ScrapeData, that includes additional methods for handling stats.
 */
class TrackerStatistics @Inject constructor(@TrackerStatisticsSecureStorage storage: Storage) : Storage(storage.database) {
    /**
     * Receives the entire response dictionary of a tracker's response, the relevant tracker and the related infohash.
     * Updates the storage with the bencoded Scrape from the relevant data in the response dictionary.
     */
    fun addScrape(responseDict: HashMap<*, *>,
                  infohash: String,
                  tracker: String) : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val complete = (responseDict.getOrDefault("complete", 0) as Int)
            val download = (responseDict.getOrDefault("downloaded", 0) as Int)
            val incomplete = (responseDict.getOrDefault("incomplete", 0) as Int)
            val name = responseDict["name"] as String?
            val scrape = Scrape(complete, download, incomplete, name)
            val scrapeKey = infohash + "_" + tracker
            val scrapeData = Bencoder.encodeStr(scrape)
            Pair(scrapeKey, scrapeData)
        }.thenCompose { (scrapeKey, scrapeData) ->
            write(scrapeKey, scrapeData.toByteArray())
        }
    }

    /**
     * Receives the failure reason of a tracker's response, the relevant tracker and the related infohash.
     * Updates the storage with the bencoded Failure with the relevant reason.
     */
     fun addFailure(infohash: String,
                    tracker: String,
                    reason: String)  : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val scrapeKey = infohash + "_" + tracker
            val scrapeData = Bencoder.encodeStr(Failure(reason))
            Pair(scrapeKey, scrapeData)
        }.thenCompose { (scrapeKey, scrapeData) ->
            write(scrapeKey, scrapeData.toByteArray())
        }

    }

    companion object {
        /**
         * Creates a complete url in order to scrape the given tracker of the torrent identified by the infohash.
         */
         fun createScrapeURL(infohash: String, tracker: String) : String {
            val idx = tracker.lastIndexOf('/')
            val scrapeURL = tracker.replaceRange(idx + 1, idx + "announce".length + 1, "scrape")
            val encodedInfohash = hexToURL(infohash)
            val query = "info_hash=$encodedInfohash"
            return ("$scrapeURL?$query")
        }
    }
}