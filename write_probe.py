content = '''package org.aethra.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.Assert.*

/** Minimal compiler probe to test if Kotlin compiler drops reachable code. */
class CompilerProbe {
    companion object {
        val initCounter = AtomicInteger(0)
        val runCounter = AtomicInteger(0)
        val helperCounter = AtomicInteger(0)
        val initCompleted = AtomicBoolean(false)
        val runCompleted = AtomicBoolean(false)
    }

    init {
        initCounter.incrementAndGet()
        println("INIT-A")
        require(true) { "require should not throw" }
        initCounter.incrementAndGet()
        println("INIT-B")
        throwIfFalse(false)
        initCounter.incrementAndGet()
        println("INIT-C")
        initCompleted.set(true)
    }

    fun run(): String {
        runCounter.incrementAndGet()
        println("RUN-A")
        if (false) {
            println("UNREACHABLE")
        }
        runCounter.incrementAndGet()
        println("RUN-B")
        runCompleted.set(true)
        return "OK"
    }

    private fun throwIfFalse(value: Boolean) {
        helperCounter.incrementAndGet()
        if (value) {
            throw IllegalStateException("SHOULD-NOT-HAPPEN")
        }
    }
}

/** Test function to verify compiler behavior. */
class CompilerProbeTest {

    @Test
    fun testCompilerProbe() {
        val probe = CompilerProbe()
        val result = probe.run()
        
        val initCount = CompilerProbe.initCounter.get()
        val runCount = CompilerProbe.runCounter.get()
        val helperCount = CompilerProbe.helperCounter.get()
        val initDone = CompilerProbe.initCompleted.get()
        val runDone = CompilerProbe.runCompleted.get()
        
        println("initCounter: $initCount (expected 3)")
        println("runCounter: $runCount (expected 2)")
        println("helperCounter: $helperCount (expected 1)")
        println("initCompleted: $initDone (expected true)")
        println("runCompleted: $runDone (expected true)")
        println("result: $result (expected OK)")
        
        assertEquals(3, initCount, "init block should execute all 3 increments")
        assertEquals(2, runCount, "run() should execute both increments")
        assertEquals(1, helperCounter, "helper should be called once")
        assertTrue(initDone, "init should complete")
        assertTrue(runDone, "run should complete")
        assertEquals("OK", result)
    }
}
'''

with open('/data/workspace/AETHRA-worktrees/opencode/core/src/test/kotlin/org/aethra/core/CompilerProbeTest.kt', 'w') as f:
    f.write(content)
print('Done')