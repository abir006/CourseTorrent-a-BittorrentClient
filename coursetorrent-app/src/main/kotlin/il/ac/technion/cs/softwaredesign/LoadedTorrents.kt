package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject

class LoadedTorrents @Inject constructor(@LoadedTorrentsSecureStorage storage: Storage) : Storage(storage.database) {
}
