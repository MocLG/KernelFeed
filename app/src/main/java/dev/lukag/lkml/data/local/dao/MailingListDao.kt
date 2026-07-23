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

package dev.lukag.lkml.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.lukag.lkml.data.local.entity.MailingListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailingListDao {

    /**
     * Catalogue order: pinned first, then curated, then by recent activity.
     *
     * Alphabetical order would bury the dozen lists people actually open under ~340
     * narrow subsystem and CI archives.
     */
    @Query(
        """
        SELECT * FROM mailing_lists
        ORDER BY isPinned DESC, isFeatured DESC, lastActivityEpochMillis DESC, slug ASC
        """,
    )
    fun observeAll(): Flow<List<MailingListEntity>>

    /**
     * Substring match over slug, title and description.
     *
     * Plain `LIKE` rather than FTS: 353 short rows are trivial to scan, and an FTS table
     * would tokenise away the hyphens that make up most list names (`linux-arm-kernel`),
     * which is exactly what users type.
     */
    @Query(
        """
        SELECT * FROM mailing_lists
        WHERE slug LIKE '%' || :query || '%'
           OR title LIKE '%' || :query || '%'
           OR description LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN slug = :query THEN 0
                 WHEN slug LIKE :query || '%' THEN 1
                 ELSE 2 END,
            isPinned DESC, isFeatured DESC, lastActivityEpochMillis DESC
        LIMIT 100
        """,
    )
    fun search(query: String): Flow<List<MailingListEntity>>

    @Query("SELECT * FROM mailing_lists WHERE slug = :slug")
    fun observeList(slug: String): Flow<MailingListEntity?>

    @Query("SELECT COUNT(*) FROM mailing_lists")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(lists: List<MailingListEntity>): List<Long>

    @Query(
        """
        UPDATE mailing_lists
           SET title = :title,
               description = :description,
               lastActivityEpochMillis = :lastActivity,
               isFeatured = :isFeatured
         WHERE slug = :slug
        """,
    )
    suspend fun updateCatalogFields(
        slug: String,
        title: String,
        description: String?,
        lastActivity: Long,
        isFeatured: Boolean,
    )

    /**
     * Refreshes the catalogue without disturbing `isPinned`, which is user state the
     * manifest knows nothing about and a REPLACE would silently discard.
     */
    @Transaction
    suspend fun upsertCatalog(lists: List<MailingListEntity>) {
        val inserted = insertIgnoring(lists)
        lists.forEachIndexed { index, list ->
            if (inserted[index] == -1L) {
                updateCatalogFields(
                    slug = list.slug,
                    title = list.title,
                    description = list.description,
                    lastActivity = list.lastActivityEpochMillis,
                    isFeatured = list.isFeatured,
                )
            }
        }
    }

    @Query("UPDATE mailing_lists SET isPinned = :pinned WHERE slug = :slug")
    suspend fun setPinned(slug: String, pinned: Boolean)
}
