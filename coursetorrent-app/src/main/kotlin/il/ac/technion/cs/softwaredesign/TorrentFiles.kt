package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import java.util.ArrayList
import java.util.concurrent.CompletableFuture

/**
 * A wrapper class to storage of the form infohash->TorrentFiles.
 */
class TorrentFiles @Inject constructor(@TorrentFilesSecureStorage  storage: Storage) : Storage(storage.database) {

}