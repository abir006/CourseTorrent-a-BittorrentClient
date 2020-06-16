package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject

/**
 * A wrapper class to storage of the form infohash->List<Piece>
 */
class Pieces @Inject constructor(@PiecesSecureStorage  storage: Storage) : Storage(storage.database) { }