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

    @Provides @Inject @TrackerStatisticsSecureStorage
    fun providesTrackerStatistics(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("TrackerStatisticsDB".toByteArray()))
    }

    @Provides @Inject @TorrentStatisticsSecureStorage
    fun providesTorrentStatistics(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("TorrentStatisticsDB".toByteArray()))
    }

    @Provides @Inject @TorrentFilesSecureStorage
    fun providesTorrentFiles(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("TorrentFilesDB".toByteArray()))
    }

    @Provides @Inject @PiecesSecureStorage
    fun providesPieces(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("PiecesDB".toByteArray()))
    }

    @Provides @Inject @LoadedTorrentsSecureStorage
    fun providesLoadedTorrents(factory: SecureStorageFactory): Storage {
        return Storage(factory.open("LoadedTorrentsDB".toByteArray()))
    }
}