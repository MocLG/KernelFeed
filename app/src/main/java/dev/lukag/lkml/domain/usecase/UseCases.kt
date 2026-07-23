package dev.lukag.lkml.domain.usecase

import dev.lukag.lkml.di.DefaultDispatcher
import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.MessageNode
import dev.lukag.lkml.domain.parser.BodyParser
import dev.lukag.lkml.domain.parser.ThreadTreeBuilder
import dev.lukag.lkml.domain.repository.ThreadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Observes a thread's messages and rebuilds its reply tree.
 *
 * `flowOn(Default)` moves both the Room→domain mapping and the tree construction off the
 * main thread. Because Room re-emits on every write, this also means a thread being
 * streamed in from its mbox re-renders progressively — the tree simply grows as batches
 * land, with no explicit progress plumbing.
 */
class GetThreadTreeUseCase @Inject constructor(
    private val repository: ThreadRepository,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {
    operator fun invoke(rootMessageId: String): Flow<List<MessageNode>> =
        repository.observeMessages(rootMessageId)
            .map { ThreadTreeBuilder.build(it) }
            .flowOn(default)
}

/**
 * Turns a raw body into renderable blocks.
 *
 * Kept out of composables entirely: a patch body can be tens of thousands of lines, and
 * scanning it during composition would drop frames on every recomposition and on every
 * scroll that brought the message back into the viewport.
 */
class ParseMessageBodyUseCase @Inject constructor(
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {
    suspend operator fun invoke(body: String): List<BodyBlock> =
        withContext(default) { BodyParser.parse(body) }

    /** Parses many bodies at once, used to warm a thread's blocks after it loads. */
    suspend fun forAll(messages: List<Message>): Map<String, List<BodyBlock>> =
        withContext(default) {
            messages.associate { it.messageId to BodyParser.parse(it.body) }
        }
}

/** Nearest-ancestor-first path to a message, for jump-to-parent navigation. */
class GetAncestorsUseCase @Inject constructor(
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {
    suspend operator fun invoke(roots: List<MessageNode>, messageId: String): List<Message> =
        withContext(default) { ThreadTreeBuilder.ancestorsOf(roots, messageId) }
    }
