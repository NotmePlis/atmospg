package com.atmosphere.companion

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.atmosphere.companion.databinding.SheetLogDetailBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Date

/** Panel detail dari bawah untuk satu kartu riwayat. */
object LogDetailSheet {
    private val full = java.text.SimpleDateFormat("d MMM yyyy HH:mm:ss", java.util.Locale("id", "ID"))

    fun show(ctx: Context, row: LogRow) {
        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_AtmospherePG_BottomSheetDialog)
        val b = SheetLogDetailBinding.inflate(LayoutInflater.from(ctx))
        dialog.setContentView(b.root)

        val tx = Ui.toTx(row)
        val level = tx?.status?.level ?: Ui.levelOfLog(row.result)
        val text = Ui.textColor(level)
        val container = Ui.containerColor(level)
        b.tvSheetAmount.text = (tx?.amount ?: Ui.amountValue(row.summary))?.let { Ui.rupiah(it) } ?: "—"
        b.tvSheetStatus.text = tx?.status?.label ?: Ui.delivery(row).first
        b.tvSheetStatus.setTextColor(ContextCompat.getColor(ctx, text))
        b.tvSheetStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, container))
        b.tvSheetSummary.text = row.summary

        // Alur: diterima di HP -> dikirim -> diproses backend
        val sent = row.stage >= Stage.SENDING && row.sentAt > 0L
        addRow(ctx, b.sheetRows, "Diterima di HP", full.format(Date(row.ts)))
        if (row.eventId != null) {
            addRow(ctx, b.sheetRows, "Dikirim ke server", if (sent) full.format(Date(row.sentAt)) else "Menunggu")
            addRow(
                ctx, b.sheetRows, "Diproses server",
                when {
                    row.stage == Stage.ACKED && row.ackedAt > 0L -> full.format(Date(row.ackedAt)) + " • " + row.result.removePrefix("server: ")
                    row.stage == Stage.ACKED -> row.result.removePrefix("server: ")
                    row.stage == Stage.REJECTED -> row.result
                    else -> "Belum ada jawaban"
                },
            )
        } else {
            addRow(ctx, b.sheetRows, "Hasil", row.result)
        }

        if (row.eventId != null) {
            addRow(ctx, b.techRows, "Event ID", row.eventId, selectable = true)
            addRow(ctx, b.techRows, "Tahap", stageName(row.stage))
            row.attempts?.let { addRow(ctx, b.techRows, "Percobaan", it.toString()) }
            row.lastError?.let { addRow(ctx, b.techRows, "Error terakhir", it, selectable = true) }
        } else {
            addRow(ctx, b.techRows, "Event ID", "- (bukan event pembayaran)")
        }
        b.btnTech.setOnClickListener {
            val open = b.techRows.visibility != View.VISIBLE
            b.techRows.visibility = if (open) View.VISIBLE else View.GONE
            b.ivTech.rotation = if (open) 180f else 0f
        }

        b.btnSheetClose.setOnClickListener { dialog.dismiss() }
        if (row.canRetry && row.eventId != null) {
            b.btnSheetRetry.visibility = View.VISIBLE
            b.btnSheetRetry.setOnClickListener {
                dialog.dismiss()
                val act = ctx as? MainActivity
                when {
                    !AppConfig.get(ctx).liveEnabled -> act?.feedback("Mode uji: pengiriman ditahan. Aktifkan live dulu.", Level.WARN)
                    RelayEngine.retryEvent(ctx, row.eventId) -> act?.watchRetry(row.eventId)
                    else -> act?.feedback("Event sudah tidak ada di antrean.", Level.BAD)
                }
            }
        }
        dialog.behavior.apply {
            skipCollapsed = true // langsung terbuka penuh; swipe ke bawah menutup, scroll isi tetap jalan
            isHideable = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun stageName(stage: Int) = when (stage) {
        Stage.QUEUED -> "Antre"
        Stage.SENDING -> "Mengirim / belum diakui"
        Stage.ACKED -> "Diakui server"
        Stage.REJECTED -> "Ditolak server"
        else -> "-"
    }

    private fun addRow(ctx: Context, parent: LinearLayout, label: String, value: String, selectable: Boolean = false) {
        val dp = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        row.addView(TextView(ctx).apply {
            this.text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant))
        })
        row.addView(TextView(ctx).apply {
            this.text = value
            textSize = 14f
            setTextIsSelectable(selectable)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
        })
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
