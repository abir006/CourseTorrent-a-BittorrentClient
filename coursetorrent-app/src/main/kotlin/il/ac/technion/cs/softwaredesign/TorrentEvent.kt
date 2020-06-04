package il.ac.technion.cs.softwaredesign

enum class TorrentEvent {
    STARTED,
    STOPPED,
    COMPLETED,
    REGULAR // i.e., announce at a regular interval
}