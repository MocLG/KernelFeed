/*
 * LKML — an offline-first reader for the lore.kernel.org mailing-list archives.
 * Copyright (C) 2026 Luka Gejak
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3, as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Alternatively, this file is available under a commercial licence that lifts
 * the obligations of the GPL. Enquiries: lukagejak5@gmail.com
 */

package dev.lukag.lkml.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lukag.lkml.BuildConfig
import dev.lukag.lkml.data.local.LkmlDatabase
import dev.lukag.lkml.data.remote.BotChallengeInterceptor
import dev.lukag.lkml.data.remote.LoreApi
import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.data.remote.UserAgentInterceptor
import dev.lukag.lkml.data.repository.ThreadRepositoryImpl
import dev.lukag.lkml.domain.repository.ThreadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * A contactable, non-browser User-Agent.
     *
     * This is required, not decorative — see `UserAgentInterceptor` for what the archive
     * does to browser-shaped and curl-shaped clients. It also follows public-inbox's
     * request that automated readers identify themselves.
     */
    private const val USER_AGENT = "lkml-app/${BuildConfig.VERSION_NAME} (Android; +https://lore.kernel.org)"

    private const val CACHE_BYTES = 32L * 1024 * 1024

    @Provides
    @Singleton
    fun okHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(context.cacheDir.resolve("http"), CACHE_BYTES))
            .addInterceptor(UserAgentInterceptor(USER_AGENT))
            .addInterceptor(BotChallengeInterceptor())
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BASIC only: mbox bodies are megabytes and would flood logcat.
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            // Generous read timeout: a large thread's mbox is a slow, streaming response.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(LoreUrls.BASE)
            // Responses are HTML/mbox, never JSON; scalars covers the String case and the
            // rest is taken as a raw streaming ResponseBody.
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(client)
            .build()

    @Provides
    @Singleton
    fun loreApi(retrofit: Retrofit): LoreApi = retrofit.create(LoreApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LkmlDatabase =
        Room.databaseBuilder(context, LkmlDatabase::class.java, LkmlDatabase.NAME)
            // The database is a cache of a public archive: if a future schema change is
            // ever shipped without a migration, discarding and re-fetching is correct and
            // costs the user nothing but bandwidth.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun threadDao(db: LkmlDatabase) = db.threadDao()

    @Provides
    fun messageDao(db: LkmlDatabase) = db.messageDao()

    @Provides
    fun mailingListDao(db: LkmlDatabase) = db.mailingListDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindThreadRepository(impl: ThreadRepositoryImpl): ThreadRepository
}
