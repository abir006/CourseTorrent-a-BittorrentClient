package il.ac.technion.cs.softwaredesign

import java.net.URL

/**
 * A minimized wrapper for URL, aimed for providing the ability to mock http requests and enabling extensions in the
 * future suited for our URL needs by the torrent app.
 */
class HttpClient {
    lateinit var url: URL

    fun setURL(url: String){
        this.url = URL(url)
    }

    fun getResponse() : ByteArray{
        return url.readBytes()
    }
}