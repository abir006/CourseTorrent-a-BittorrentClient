package il.ac.technion.cs.softwaredesign

data class ConnectedPeer(
    val knownPeer: KnownPeer,
    val amChoking: Boolean,
    val amInterested: Boolean,
    val peerChoking: Boolean,
    val peerInterested: Boolean,
    val completedPercentage: Double,
    val averageSpeed: Double
)