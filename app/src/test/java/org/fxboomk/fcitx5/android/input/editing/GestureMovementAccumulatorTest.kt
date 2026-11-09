/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMovementAccumulatorTest {
    private val movement = GestureMovementAccumulator(16f, 32f)

    @Test
    fun ignoresEntryGestureUntilASeparateDown() {
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(120f, 180f))
        movement.start(100f, 100f)
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(116f, 100f))
    }

    @Test
    fun smallUpdatesAccumulateAndLargeUpdatesKeepEveryCharacterStep() {
        movement.start(0f, 0f)
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(7f, 0f))
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(15f, 0f))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(19f, 0f))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 3), movement.move(65f, 0f))
    }

    @Test
    fun reversalMovesBackAndPerpendicularJitterDoesNotSelectAnotherLine() {
        movement.start(0f, 0f)
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 2), movement.move(32f, 18f))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = -1), movement.move(16f, 21f))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = -1), movement.move(0f, 23f))
    }

    @Test
    fun verticalStepsAreLargerAndDirectionCanChangeDuringTheStroke() {
        movement.start(0f, 100f)
        assertEquals(GestureMovementAccumulator.Movement(vertical = -2), movement.move(3f, 36f))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(19f, 36f))
        assertEquals(GestureMovementAccumulator.Movement(vertical = 1), movement.move(19f, 68f))
    }

    @Test
    fun liftingOrCancellingDiscardsResidualMotion() {
        movement.start(0f, 0f)
        movement.move(15f, 0f)
        movement.cancel()
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(100f, 100f))
        movement.start(200f, 200f)
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(201f, 201f))
    }

    @Test
    fun slowTimestampedMotionRetainsExactCharacterAndLineSteps() {
        movement.start(0f, 0f, 100L)
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(16f, 0f, 260L))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(32f, 0f, 420L))
        assertEquals(GestureMovementAccumulator.Movement(vertical = 1), movement.move(32f, 32f, 580L))
    }

    @Test
    fun fasterTravelOverTheSameDistanceMovesMoreCharactersWithAFourTimesLimit() {
        val slow = horizontalTravel(160f, 1000L, 1)
        val fast = horizontalTravel(160f, 100L, 1)
        val fastest = horizontalTravel(160f, 1L, 1)
        assertEquals(10, slow)
        assertEquals(40, fast)
        assertEquals(fast, fastest)
    }

    @Test
    fun increasingFingerSpeedIncreasesMovementWithinOneStroke() {
        movement.start(0f, 0f, 0L)
        assertEquals(1, movement.move(16f, 0f, 80L).horizontal)
        assertEquals(2, movement.move(32f, 0f, 100L).horizontal)
        assertEquals(4, movement.move(48f, 0f, 110L).horizontal)
    }

    @Test
    fun verticalMotionAcceleratesButHorizontalJitterDoesNotAddACharacterStep() {
        movement.start(0f, 0f, 0L)
        assertEquals(GestureMovementAccumulator.Movement(vertical = 1), movement.move(1f, 32f, 160L))
        assertEquals(GestureMovementAccumulator.Movement(vertical = 4), movement.move(2f, 64f, 180L))
    }

    @Test
    fun slowReversalDropsThePriorDirectionsAcceleratedFraction() {
        movement.start(0f, 0f, 0L)
        assertEquals(4, movement.move(18f, 0f, 9L).horizontal)
        assertEquals(-1, movement.move(2f, 0f, 169L).horizontal)
        assertEquals(-1, movement.move(-14f, 0f, 329L).horizontal)
    }

    @Test
    fun stationaryFingerDoesNotMoveAfterAFastStroke() {
        movement.start(0f, 0f, 0L)
        assertEquals(4, movement.move(18f, 0f, 9L).horizontal)
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(18f, 0f, 10L))
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(18f, 0f, 2000L))
        assertEquals(1, movement.move(34f, 0f, 2160L).horizontal)
    }

    @Test
    fun missingDuplicateAndBackwardTimestampsNeverCreateAccelerationSpikes() {
        movement.start(0f, 0f, 100L)
        assertEquals(1, movement.move(16f, 0f, 100L).horizontal)
        assertEquals(1, movement.move(32f, 0f, 90L).horizontal)
        assertEquals(1, movement.move(48f, 0f, 90L).horizontal)
        assertEquals(1, movement.move(64f, 0f).horizontal)
        assertEquals(1, movement.move(80f, 0f, 110L).horizontal)
    }

    @Test
    fun equalPhysicalSpeedIsConsistentAcrossDensityAndEventIntervals() {
        val singleEvent = horizontalTravel(160f, 200L, 1)
        val batchedEvents = horizontalTravel(160f, 200L, 10)
        val highDensity = horizontalTravel(160f, 200L, 10, density = 3f)
        assertEquals(22, singleEvent)
        assertEquals(singleEvent, batchedEvents)
        assertEquals(singleEvent, highDensity)
    }

    @Test
    fun startingANewStrokeClearsAcceleratedFractionsAndTimeHistory() {
        movement.start(0f, 0f, 0L)
        movement.move(18f, 0f, 9L)
        movement.cancel()
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(100f, 0f, 10L))
        movement.start(200f, 200f, 1000L)
        assertEquals(GestureMovementAccumulator.Movement(), movement.move(208f, 200f, 1160L))
        assertEquals(GestureMovementAccumulator.Movement(horizontal = 1), movement.move(216f, 200f, 1320L))
    }

    private fun horizontalTravel(distanceDp: Float, durationMs: Long, events: Int, density: Float = 1f): Int {
        val accumulator = GestureMovementAccumulator(16f * density, 32f * density, density)
        accumulator.start(0f, 0f, 0L)
        var steps = 0
        for (index in 1..events) {
            val result = accumulator.move(distanceDp * density * index / events, 0f, durationMs * index / events)
            assertTrue(result.horizontal >= 0)
            steps += result.horizontal
        }
        return steps
    }
}
