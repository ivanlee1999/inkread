package me.ash.reader.infrastructure.annotation

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import me.ash.reader.domain.annotation.AnnotationOutbox
import me.ash.reader.domain.annotation.AnnotationRepository

@Module
@InstallIn(SingletonComponent::class)
internal object AnnotationModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AnnotationsDatabase =
        Room.databaseBuilder(context, AnnotationsDatabase::class.java, "Annotations").build()
    @Provides @Singleton
    fun implementation(database: AnnotationsDatabase): DefaultAnnotationRepository =
        DefaultAnnotationRepository(database.annotations(), RoomAnnotationTransactions(database))
    @Provides @Singleton
    fun repository(implementation: DefaultAnnotationRepository): AnnotationRepository = implementation
    @Provides @Singleton
    fun outbox(implementation: DefaultAnnotationRepository): AnnotationOutbox = implementation
}
