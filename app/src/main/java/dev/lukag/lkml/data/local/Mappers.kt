package dev.lukag.lkml.data.local

import dev.lukag.lkml.data.local.entity.MessageEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity
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
)

fun ThreadSummary.toEntity(inFeed: Boolean) = ThreadEntity(
    rootMessageId = rootMessageId,
    subject = subject,
    lastActivityEpochMillis = lastActivityEpochMillis,
    messageCount = messageCount,
    latestAuthor = latestAuthor,
    isSaved = isSaved,
    isCached = isCached,
    inFeed = inFeed,
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
