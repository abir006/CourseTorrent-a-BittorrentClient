package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import com.google.inject.Provides
import dev.misfitlabs.kotlinguice4.KotlinModule
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import il.ac.technion.cs.softwaredesign.storage.SecureStorageModule
import java.util.concurrent.CompletableFuture

class CourseTorrentModule : KotlinModule() {
    override fun configure() {
        install(SecureStorageModule())
        bind<HttpClient>().toInstance(HttpClient())
    }

    @Provides @Inject @AnnouncesSecureStorage
    fun providesAnnounces(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("AnnouncesDB".toByteArray()))
    }

    @Provides @Inject @PeersSecureStorage
    fun providesPeers(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("PeersDB".toByteArray()))
    }

    @Provides @Inject @StatisticsSecureStorage
    fun providesStatistics(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("StatisticsDB".toByteArray()))
    }
}