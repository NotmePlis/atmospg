package com.atmosphere.companion

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.atmosphere.companion.databinding.ItemTechBinding
import com.atmosphere.companion.databinding.ItemTxBinding
import java.text.SimpleDateFormat
import java.util.Locale

/** Baris transaksi ringkas: nominal, waktu, badge status. Dipakai di Beranda dan Riwayat. */
object TxRow {
    fun bind(b: ItemTxBinding, tx: Ui.Tx) {
        val ctx = b.root.context
        val level = tx.status.level
        val fg = ContextCompat.getColor(ctx, Ui.textColor(level))
        val bg = ContextCompat.getColor(ctx, Ui.containerColor(level))
        b.ivTx.setImageResource(tx.status.icon)
        ImageViewCompat.setImageTintList(b.ivTx, ColorStateList.valueOf(fg))
        b.ivTx.backgroundTintList = ColorStateList.valueOf(bg)
        b.tvTxAmount.text = tx.amount?.let { Ui.rupiah(it) } ?: "—"
        b.tvTxTime.text = Ui.shortTime(tx.row.ts)
        b.tvTxBadge.text = tx.status.label
        b.tvTxBadge.setTextColor(fg)
        b.tvTxBadge.backgroundTintList = ColorStateList.valueOf(bg)
        b.root.setOnClickListener { LogDetailSheet.show(b.root.context, tx.row) }
    }
}

private fun key(r: LogRow) = r.eventId ?: "${r.ts}|${r.summary}"

/** Daftar transaksi (Riwayat → Transaksi). */
class TxAdapter : RecyclerView.Adapter<TxAdapter.VH>() {
    class VH(val b: ItemTxBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<Ui.Tx> = emptyList()

    fun submit(list: List<Ui.Tx>) {
        val old = items
        if (old.size == list.size && old.indices.all { old[it].row == list[it].row && old[it].status == list[it].status }) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = key(old[o].row) == key(list[n].row)
            override fun areContentsTheSame(o: Int, n: Int) = old[o].row == list[n].row && old[o].status == list[n].status
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTxBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = TxRow.bind(holder.b, items[position])
}

/** Log teknis: semua baris (termasuk duplikat, diabaikan, diagnostik, peringatan). */
class TechAdapter : RecyclerView.Adapter<TechAdapter.VH>() {
    class VH(val b: ItemTechBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<LogRow> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale("id", "ID"))

    fun submit(list: List<LogRow>) {
        if (list == items) return
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = key(old[o]) == key(list[n])
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTechBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val ctx = holder.b.root.context
        holder.b.tvTechHead.text = "${fmt.format(java.util.Date(row.ts))}  ${row.result}"
        holder.b.tvTechHead.setTextColor(ContextCompat.getColor(ctx, Ui.textColor(Ui.levelOfLog(row.result))))
        holder.b.tvTechBody.text = row.summary
        holder.b.root.setOnClickListener { LogDetailSheet.show(ctx, row) }
    }
}
