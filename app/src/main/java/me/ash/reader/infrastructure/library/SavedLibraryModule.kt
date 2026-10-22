package me.ash.reader.infrastructure.library

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.ash.reader.domain.library.SavedLibraryRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object SavedLibraryModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SavedLibraryDatabase =
        Room.databaseBuilder(context, SavedLibraryDatabase::class.java, "SavedLibrary").build()
    @Provides
    fun dao(database: SavedLibraryDatabase): SavedLibraryDao = database.savedLibraryDao()
    @Provides @Singleton
    fun limits(): SavedStorageLimits = SavedStorageLimits()
    @Provides @Singleton
    fun files(@ApplicationContext context: Context, limits: SavedStorageLimits): SavedLibraryFiles =
        SavedLibraryFiles(context.filesDir.resolve("saved-library"), limits)
    @Provides @Singleton
    fun downloader(limits: SavedStorageLimits): SavedArticleDownloader = PublicSavedArticleDownloader(limits)
    @Provides @Singleton
    fun scheduler(workManager: WorkManager): SavedDownloadScheduler = WorkManagerSavedDownloadScheduler(workManager)
    @Provides @Singleton
    fun implementation(dao: SavedLibraryDao, files: SavedLibraryFiles, downloader: SavedArticleDownloader,
        scheduler: SavedDownloadScheduler): DefaultSavedLibraryRepository = DefaultSavedLibraryRepository(dao, files, downloader, scheduler)
    @Provides @Singleton
    fun repository(implementation: DefaultSavedLibraryRepository): SavedLibraryRepository = implementation
}
