package org.aethra.core

import org.aethra.core.policy.CorePolicyEngine
import org.aethra.core.policy.RiskTier
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PolicyEngineConcurrencyTest {
    @Test
    fun approvalCanOnlyBeConsumedOnceUnderConcurrency() {
        val policy = CorePolicyEngine()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(20) { round ->
                val approval = assertNotNull(
                    policy.requestApproval(
                        actor = "user",
                        action = "execute",
                        target = "device-$round",
                        normalizedPayload = "install:package-$round",
                        risk = RiskTier.TIER_2
                    )
                )
                val barrier = CyclicBarrier(9)
                val attempts = (1..8).map {
                    executor.submit<Boolean> {
                        barrier.await(5, TimeUnit.SECONDS)
                        policy.consumeApproval(
                            approval.id,
                            "user",
                            "execute",
                            "device-$round",
                            "install:package-$round"
                        )
                    }
                }

                barrier.await(5, TimeUnit.SECONDS)
                val accepted = attempts.count { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, accepted, "approval $round must be consumed by exactly one concurrent caller")
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
