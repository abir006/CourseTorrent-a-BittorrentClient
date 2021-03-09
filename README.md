# A Kotlin Torrent like client

## Authors
* Abir Shaked, 204596597
* Shahak Ben-Kalifa, 311242440

### Implementation Summary
Our whole implementation is based on composing futures and combining while futures are not dependent, or not using the
same resources, all the while without calling get() or join() outside the main. We added private fields, for everything
that does not require persistence, e.g connected peers. We created persistent databases for storing data about pieces, 
connected peers, torrent files and others, in order to save those dataclasses, we updated our serialization using 
bencoder as well, to store an instance of the dataclass, as well as decoding bytearray inside data classes. 

### Testing Summary
In order to test the network based operations, we used a futuristic runAsync block in order to hold one side of a user
or server side socket, and another in order to hold the app side. This way we simulated network communication over
sockets and tested requests.
