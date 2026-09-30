package com.naua_security_mirage.app.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.CustomWebsite
import com.naua_security_mirage.app.util.AnimationHelper
import com.naua_security_mirage.app.ui.view.MirageSwitch

class CustomWebsiteAdapter(
    private val context: Context,
    private val onToggle: (site: CustomWebsite, isEnabled: Boolean) -> Unit,
    private val onDelete: (site: CustomWebsite) -> Unit
) : RecyclerView.Adapter<CustomWebsiteAdapter.WebsiteViewHolder>() {

    private var items: List<CustomWebsite> = emptyList()
    var isLightContext: Boolean = false
    var customTextColor: Int = 0
    var customSwitchColor: Int = 0

    fun submitList(newItems: List<CustomWebsite>, forceNotify: Boolean = false) {
        val oldList = items
        val newList = newItems.map { it.copy() }
        if (forceNotify) {
            items = newList
            notifyDataSetChanged()
            return
        }
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].domain.equals(newList[newItemPosition].domain, ignoreCase = true)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldList[oldItemPosition]
                val new = newList[newItemPosition]
                return old.domain == new.domain && old.isEnabled == new.isEnabled
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebsiteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_custom_website, parent, false)
        return WebsiteViewHolder(view)
    }

    override fun onBindViewHolder(holder: WebsiteViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class WebsiteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: LinearLayout = itemView.findViewById(R.id.rowWebsiteItem)
        private val containerIcon: FrameLayout = itemView.findViewById(R.id.containerSiteIcon)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivSiteIcon)
        private val tvDomain: TextView = itemView.findViewById(R.id.tvSiteDomain)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvSiteStatus)
        private val switchSite: MirageSwitch = itemView.findViewById(R.id.switchSiteProxy)
        private val btnDelete: FrameLayout = itemView.findViewById(R.id.btnDeleteSite)
        private val ivDelete: ImageView = itemView.findViewById(R.id.ivDeleteSite)

        fun bind(site: CustomWebsite) {
            tvDomain.text = site.domain

            val titleColor = if (customTextColor != 0) {
                customTextColor
            } else if (isLightContext) {
                Color.parseColor("#0F172A")
            } else {
                ContextCompat.getColor(context, R.color.ink)
            }

            val subtitleColor = if (customTextColor != 0) {
                ColorUtils.setAlphaComponent(customTextColor, 175)
            } else if (isLightContext) {
                Color.parseColor("#64748B")
            } else {
                ContextCompat.getColor(context, R.color.ink_soft)
            }

            tvDomain.setTextColor(titleColor)
            tvStatus.setTextColor(subtitleColor)
            ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(titleColor)

            if (site.isEnabled) {
                tvStatus.text = "Прямое подключение"
                tvStatus.alpha = 1.0f
            } else {
                tvStatus.text = "Через туннель (по умолчанию)"
                tvStatus.alpha = 0.6f
            }

            switchSite.setLightMode(isLightContext)
            if (customSwitchColor != 0) {
                switchSite.setActiveColor(customSwitchColor)
            } else {
                switchSite.resetColors()
            }

            switchSite.isChecked = site.isEnabled

            switchSite.setOnCheckedChangeListener { _, isChecked ->
                if (site.isEnabled != isChecked) {
                    onToggle(site, isChecked)
                }
            }

            row.setOnClickListener {
                switchSite.toggle()
            }

            btnDelete.setOnClickListener {
                AnimationHelper.bounceClick(btnDelete, minScale = 0.85f, durationMs = 150)
                onDelete(site)
            }
        }
    }
}
