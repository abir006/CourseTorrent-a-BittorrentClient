package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import com.google.inject.Provides
import dev.misfitlabs.kotlinguice4.KotlinModule
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import il.ac.technion.cs.softwaredesign.storage.SecureStorageModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.net.URL

class CourseTorrentTestModule : KotlinModule() {
    override fun configure() {
        bind<HttpClient>().toInstance(mockk(relaxed = true))
    }

    @Provides @Inject @AnnouncesSecureStorage
    fun providesAnnounces(): Storage {
        return Storage(DBSimulator("AnnounceStorage-Test"))
    }

    @Provides @Inject @PeersSecureStorage
    fun providesPeers(): Storage {
        return Storage(DBSimulator("PeersStorage-Test"))
    }

    @Provides @Inject @StatisticsSecureStorage
    fun providesStatistics(): Storage {
        return Storage(DBSimulator("StatisticsStorage-Test"))
    }
}