package com.newendian.retichat.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Hold the stack for [block], which is told whether it is ready, and give the
 * hold back exactly once on every path: return, throw, or cancellation (a
 * budget running out). [acquire] is inside the try because it counts its hold
 * before it can suspend or throw, so from its first line a release is owed.
 */
internal suspend fun <T> holdingStack(
    acquire: suspend () -> Boolean,
    release: () -> Unit,
    block: suspend (ready: Boolean) -> T,
): T {
    try {
        return block(acquire())
    } finally {
        release()
    }
}

/**
 * The app's hold on the stack while it is on screen, observed on
 * ProcessLifecycleOwner: ON_START when the first activity starts, ON_STOP once
 * the last has stopped, never for a rotation. A lifecycle's ON_START and
 * ON_STOP strictly alternate, so each hold taken is given back once.
 *
 * Until 2026-09-25 MainActivity acquired in onCreate and in onStart and
 * released only in onStop, so every activity creation leaked one hold.
 * [acquire] must count its hold before it returns (not on a coroutine it
 * launches), or an ON_STOP could give back a hold not yet taken (§5).
 */
internal class ForegroundHold(
    private val acquire: () -> Unit,
    private val release: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = acquire()
    override fun onStop(owner: LifecycleOwner) = release()
}
