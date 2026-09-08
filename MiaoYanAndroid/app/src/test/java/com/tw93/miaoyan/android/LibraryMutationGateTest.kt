package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.core.LibraryMutationGate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMutationGateTest {
    @Test
    fun serializesParallelMutationOperations() = runBlocking {
        val activeOperations = AtomicInteger(0)
        val maximumConcurrentOperations = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            LibraryMutationGate.withExclusiveAccess {
                val active = activeOperations.incrementAndGet()
                maximumConcurrentOperations.updateAndGet { current -> maxOf(current, active) }
                firstEntered.complete(Unit)
                releaseFirst.await()
                activeOperations.decrementAndGet()
            }
        }
        firstEntered.await()

        val second = async {
            LibraryMutationGate.withExclusiveAccess {
                val active = activeOperations.incrementAndGet()
                maximumConcurrentOperations.updateAndGet { current -> maxOf(current, active) }
                secondEntered.complete(Unit)
                activeOperations.decrementAndGet()
            }
        }
        delay(50)
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertTrue(secondEntered.isCompleted)
        assertEquals(1, maximumConcurrentOperations.get())
    }
}
