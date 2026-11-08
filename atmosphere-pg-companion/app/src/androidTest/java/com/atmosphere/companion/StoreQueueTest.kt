package com.atmosphere.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uji instrumentasi: antrean durable dan dedupe (jalankan di perangkat/emulator). */
@RunWith(AndroidJUnit4::class)
class StoreQueueTest {
    private lateinit var store: Store

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = Store.get(ctx)
        while (true) store.remove(store.head()?.eventId ?: break)
    }

    @Test fun sameEventIdIsQueuedOnlyOnce() {
        val id = "t-${System.nanoTime()}"
        assertTrue(store.enqueue(id, "{}", 1L))
        assertFalse(store.enqueue(id, "{}", 2L))
    }

    @Test fun removedEventIsNotQueuedAgainOnReplay() {
        val id = "t-${System.nanoTime()}"
        assertTrue(store.enqueue(id, "{}", 1L))
        store.remove(id) // server sudah mengakui
        assertFalse("replay setelah terkirim tidak boleh antre ulang", store.enqueue(id, "{}", 3L))
        assertNull(store.head()?.takeIf { it.eventId == id })
    }

    @Test fun contentHashDedupeRespectsWindow() {
        val h = "hash-${System.nanoTime()}"
        assertTrue(store.claimContent(h, 1_000L, 180_000L))
        assertFalse(store.claimContent(h, 100_000L, 180_000L))
        assertTrue(store.claimContent(h, 200_000L, 180_000L))
    }

    @Test fun orderIsPreservedAndRetryKeepsEvent() {
        val a = "a-${System.nanoTime()}"
        assertTrue(store.enqueue(a, "{}", 1L))
        assertTrue(store.enqueue("b-${System.nanoTime()}", "{}", 2L))
        assertEquals(a, store.head()?.eventId)
        store.markRetry(a, 1, Long.MAX_VALUE)
        assertEquals(2, store.count())
        assertEquals(1, store.head()?.attempts)
    }

    @Test fun rejectedEventDoesNotBlockQueueAndRetryKeepsSameEventId() {
        val dead = "dead-${System.nanoTime()}"
        val next = "next-${System.nanoTime()}"
        assertTrue(store.enqueue(dead, "{\"x\":1}", 1L))
        assertTrue(store.enqueue(next, "{}", 2L))
        store.markDead(dead, "HTTP 400")
        assertEquals("antrean maju melewati event ditolak", next, store.head()?.eventId)
        assertEquals(1, store.deadCount())
        store.resetBackoff() // tombol Coba Lagi
        assertEquals(0, store.deadCount())
        assertEquals("event_id yang sama kembali ke kepala antrean", dead, store.head()?.eventId)
        assertEquals("{\"x\":1}", store.head()?.payload)
    }

    @Test fun stageIsTrackedPerEvent() {
        val id = "stg-${System.nanoTime()}"
        assertTrue(store.enqueue(id, "{}", 1L))
        store.addLog(id, "uji", "antre", Stage.QUEUED)
        store.setStage(id, Stage.SENDING, "gagal: timeout")
        store.markRetry(id, 1, 0L, "timeout")
        val d = store.lastEventDiag()!!
        assertEquals(id, d.eventId)
        assertEquals(Stage.SENDING, d.stage)
        assertEquals("timeout", d.lastError)
        store.remove(id)
        store.setStage(id, Stage.ACKED, "server: matched")
        assertEquals(Stage.ACKED, store.lastEventDiag()!!.stage)
    }
}
