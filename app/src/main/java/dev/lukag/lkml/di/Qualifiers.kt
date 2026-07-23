package dev.lukag.lkml.di

import javax.inject.Qualifier

/** Disk and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * CPU-bound work: mbox → tree, body → blocks, diff → styled text.
 *
 * Injected rather than hardcoded so tests can substitute a deterministic dispatcher and
 * assert parser output without a scheduler in the way.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
