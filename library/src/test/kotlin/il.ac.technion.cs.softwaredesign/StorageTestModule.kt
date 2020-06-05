package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import com.google.inject.Provides
import dev.misfitlabs.kotlinguice4.KotlinModule
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import il.ac.technion.cs.softwaredesign.storage.SecureStorageModule

class StorageTestModule : KotlinModule() {
    override fun configure() { }

    @Provides
    fun provideJsonDB(): SecureStorage {
        return DBSimulator("StorageSimulator-Test")
    }
}