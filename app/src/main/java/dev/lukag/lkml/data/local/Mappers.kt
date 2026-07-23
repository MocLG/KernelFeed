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
