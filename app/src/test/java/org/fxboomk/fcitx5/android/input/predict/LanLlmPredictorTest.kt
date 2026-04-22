/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmPredictorTest {

    @Test
    fun requestTrackerOnlyKeepsLatestRequestActive() {
        val tracker = LanLlmPredictor.RequestTracker()

        val first = tracker.replaceActiveRequest()
        val second = tracker.replaceActiveRequest()

        assertFalse(tracker.isActive(first))
        assertTrue(tracker.isActive(second))
    }

    @Test
    fun requestTrackerInvalidationDropsCurrentRequest() {
        val tracker = LanLlmPredictor.RequestTracker()

        val requestId = tracker.replaceActiveRequest()
        tracker.invalidate()

        assertFalse(tracker.isActive(requestId))
    }
}
