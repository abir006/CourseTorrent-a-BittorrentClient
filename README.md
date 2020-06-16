# CourseApp: Assignment 0

## Authors
* Abir Shaked, 204596597
* Shahak Ben-Kalifa, 311242440

## Notes
Our implementation is not as efficient as we planned it to be since we put too much time into the assignment and didn't
get to refactoring classes, perfecting database accesses and removing redundant read & write operations which could have
been avoided using a wider span of storages.
Our tests are not nearly as broad as we planned, since we found ourselves with hardly any time to invest in it. Sadly,
too much time was invested in this assignment which left little time to testing, (although we tested small parts
while writing the code and debugging).

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

### Difficulties
Needless to say, the API is very broad and requires a lot of implementation.
A lot of the API methods caused ambiguity with others, for example: in handleSmallMessages we could have sent\received
the required pieces instead of wasting memory on another buffer, and avoiding the socket buffer purpose.
Also, after consulting with the TA we used Bencoder for serializing and deserializing our objects. Unfortunately, it
was discovered to be not efficient and caused a major slowdown, especially while serializing pieces data and major data
classes (for example turning bytearray into string, to then later send it as a bytearray into the storage).

### Feedback
This exercise was unnecessarily and extremely long and tiring which caused havoc to the whole semester.
In our view, this assignment hardly focused on any of the taught material. Only in our first few days working on this 
project, we found ourselves learning a lot about CompletableFuture library. However, very quickly our issues were 
learning the BitTorrent specification, dealing with sockets and networking, encoding and decoding bytearrays, solving
ambiguities in the exercise and plenty more technical difficulties which have nothing to do with the course material,
and in fact are very low-level, in contrast to what we signed this course to study.
In a personal note, we were both very disappointed from this course assignments, and were hoping to learn more content
related to design, and not torrents.