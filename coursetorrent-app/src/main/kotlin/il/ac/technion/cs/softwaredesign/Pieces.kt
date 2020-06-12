package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject

/**
 * A wrapper class to storage of the form ... // TODO complete
 */
class Pieces @Inject constructor(@PiecesSecureStorage  storage: Storage) : Storage(storage.database) {

}