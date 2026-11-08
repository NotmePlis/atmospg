package com.atmosphere.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val kw = NotificationFilter.compileKeywords(NotificationFilter.DEFAULT_KEYWORDS)

    private fun n(
        title: String = "",
        text: String = "",
        big: String = "",
        pkg: String = "id.dana",
        group: Boolean = false,
        ongoing: Boolean = false,
    ) = RawNotification(pkg, title, text, big, "", "", group, ongoing)

    private fun eval(r: RawNotification) = NotificationFilter.evaluate(r, "id.dana", kw)

    @Test
    fun paymentIsSent() {
        val d = eval(n(title = "Uang masuk", text = "Kamu menerima Rp10.123 dari Budi"))
        assertTrue(d is Decision.Send)
        assertEquals("Uang masuk", (d as Decision.Send).title)
    }

    @Test
    fun bigTextPreferredAsBody() {
        val d = eval(n(title = "Pembayaran", text = "Rp1", big = "Pembayaran diterima Rp25.000 via QRIS"))
        assertEquals("Pembayaran diterima Rp25.000 via QRIS", (d as Decision.Send).text)
    }

    @Test
    fun otherPackageIgnored() {
        assertEquals(Decision.Ignore, eval(n(title = "Masuk", text = "Rp5.000", pkg = "com.whatsapp")))
    }

    @Test
    fun groupSummaryAndOngoingIgnored() {
        assertEquals(Decision.Ignore, eval(n(title = "Masuk", text = "Rp5.000", group = true)))
        assertEquals(Decision.Ignore, eval(n(title = "Masuk", text = "Rp5.000", ongoing = true)))
    }

    @Test
    fun otpIsDroppedAsSensitiveWithoutContent() {
        val d = eval(n(title = "Kode OTP", text = "Kode OTP Anda 123456. Pembayaran Rp10.000"))
        assertTrue(d is Decision.Drop)
        d as Decision.Drop
        assertTrue(d.sensitive)
        assertEquals("", d.text)
    }

    @Test
    fun pinAndPasswordDropped() {
        assertTrue((eval(n(title = "Masukkan PIN", text = "Pembayaran Rp5.000")) as Decision.Drop).sensitive)
        assertTrue((eval(n(title = "Ubah password", text = "Pembayaran Rp5.000")) as Decision.Drop).sensitive)
    }

    @Test
    fun noAmountDropped() {
        val d = eval(n(title = "Promo", text = "Pembayaran lebih mudah"))
        assertTrue(d is Decision.Drop)
        assertEquals("tanpa nominal Rp (atau lebih dari satu nominal)", (d as Decision.Drop).reason)
    }

    @Test
    fun amountWithoutPaymentKeywordDropped() {
        val d = eval(n(title = "Promo", text = "Cashback hingga Rp50.000"))
        assertTrue(d is Decision.Drop)
        assertEquals("bukan notifikasi pembayaran", (d as Decision.Drop).reason)
    }

    @Test
    fun danaBisnisRealFormat() {
        val d = eval(n(title = "Pembayaran Masuk", text = "Rp1.018 diterima DANA Bisnis."))
        assertTrue(d is Decision.Send)
        assertEquals(1018L, NotificationFilter.parseAmount("Pembayaran Masuk", "Rp1.018 diterima DANA Bisnis."))
        assertEquals(1060L, NotificationFilter.parseAmount("Pembayaran Masuk", "Rp1.060 diterima DANA Bisnis."))
    }

    @Test
    fun amountParsing() {
        assertEquals(1250500L, NotificationFilter.parseAmount("", "Rp1.250.500 diterima."))
        assertEquals(500L, NotificationFilter.parseAmount("", "Rp500 diterima"))
        assertEquals(1018L, NotificationFilter.parseAmount("", "Rp1.018,00 diterima"))
        assertEquals(null, NotificationFilter.parseAmount("", "Rp5.000 dari Rp50.000"))
    }
}
