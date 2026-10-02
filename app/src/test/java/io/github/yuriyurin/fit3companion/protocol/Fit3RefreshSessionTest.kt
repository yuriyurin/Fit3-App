package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test

class Fit3RefreshSessionTest {
    @Test fun noResponseNeverCountsAsSuccess() {
        val session = Fit3RefreshSession()
        session.begin(1000)
        assertEquals(Fit3RefreshSession.Result.WAITING, session.poll(3500))
        assertEquals(Fit3RefreshSession.Result.TIMEOUT, session.poll(46000))
        assertFalse(session.active)
    }
    @Test fun eachConfirmedBatchExtendsQuietPeriod() {
        val session = Fit3RefreshSession()
        session.begin(0)
        session.confirm(1000)
        assertEquals(Fit3RefreshSession.Result.WAITING, session.poll(4999))
        session.confirm(4000)
        assertEquals(Fit3RefreshSession.Result.WAITING, session.poll(7000))
        assertEquals(Fit3RefreshSession.Result.SUCCESS, session.poll(8000))
    }
    @Test fun resetDiscardsOldConnectionEvidence() {
        val session = Fit3RefreshSession()
        session.begin(0); session.confirm(1000); session.reset(); session.begin(2000)
        assertEquals(Fit3RefreshSession.Result.WAITING, session.poll(6000))
    }
    @Test fun progressingExchangeCanLastMoreThanFortyFiveSeconds() {
        val session = Fit3RefreshSession()
        session.begin(0)
        session.confirm(44_000)
        assertEquals(Fit3RefreshSession.Result.WAITING, session.poll(46_000))
        session.confirm(47_000)
        assertEquals(Fit3RefreshSession.Result.SUCCESS, session.poll(51_000))
    }
}
