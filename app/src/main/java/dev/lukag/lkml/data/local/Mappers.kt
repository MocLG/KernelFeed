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

package dev.lukag.lkml.data.local

import dev.lukag.lkml.data.local.entity.MailingListEntity
import dev.lukag.lkml.data.local.entity.MessageEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity
import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.ThreadSummary

fun ThreadEntity.toDomain() = ThreadSummary(
    rootMessageId = rootMessageId,
    subject = subject,
    lastActivityEpochMillis = lastActivityEpochMillis,
    messageCount = messageCount,
    latestAuthor = latestAuthor,
    isSaved = isSaved,
    isCached = isCached,
    sourceList = sourceList,
)

fun ThreadSummary.toEntity() = ThreadEntity(
    rootMessageId = rootMessageId,
    subject = subject,
    lastActivityEpochMillis = lastActivityEpochMillis,
    messageCount = messageCount,
    latestAuthor = latestAuthor,
    isSaved = isSaved,
    isCached = isCached,
    sourceList = sourceList,
)

fun MailingListEntity.toDomain() = MailingList(
    slug = slug,
    title = title,
    description = description,
    lastActivityEpochMillis = lastActivityEpochMillis,
    isFeatured = isFeatured,
    isPinned = isPinned,
)

fun MailingList.toEntity() = MailingListEntity(
    slug = slug,
    title = title,
    description = description,
    lastActivityEpochMillis = lastActivityEpochMillis,
    isFeatured = isFeatured,
    isPinned = isPinned,
)

fun MessageEntity.toDomain() = Message(
    messageId = messageId,
    threadRootId = threadRootId,
    subject = subject,
    authorName = authorName,
    authorEmail = authorEmail,
    dateEpochMillis = dateEpochMillis,
    inReplyTo = inReplyTo,
    references = Converters.toStringList(references),
    body = body,
)

fun Message.toEntity() = MessageEntity(
    messageId = messageId,
    threadRootId = threadRootId,
    subject = subject,
    authorName = authorName,
    authorEmail = authorEmail,
    dateEpochMillis = dateEpochMillis,
    inReplyTo = inReplyTo,
    references = Converters.fromStringList(references),
    body = body,
)
