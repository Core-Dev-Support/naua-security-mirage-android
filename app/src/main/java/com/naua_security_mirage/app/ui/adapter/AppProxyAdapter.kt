package com.naua_security_mirage.app.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.AppInfo
import com.naua_security_mirage.app.ui.view.MirageSwitch

class AppProxyAdapter(
    private val context: Context,
    private val onToggle: (app: AppInfo, isProxied: Boolean) -> Unit
) : RecyclerView.Adapter<AppProxyAdapter.AppViewHolder>() {

    private var items: List<AppInfo> = emptyList()
    var isLightContext: Boolean = false
    var customTextColor: Int = 0
    var customSwitchColor: Int = 0

    fun submitList(newItems: List<AppInfo>, forceNotify: Boolean = false) {
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
                return oldList[oldItemPosition].packageName == newList[newItemPosition].packageName
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldList[oldItemPosition]
                val new = newList[newItemPosition]
                return old.name == new.name && old.isProxied == new.isProxied
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_proxy, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: LinearLayout = itemView.findViewById(R.id.rowAppItem)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val switchProxy: MirageSwitch = itemView.findViewById(R.id.switchAppProxy)

        fun bind(app: AppInfo) {
            tvName.text = app.name
            tvPackage.text = app.packageName
            ivIcon.setImageDrawable(app.icon)

            // Theme text styling
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

            tvName.setTextColor(titleColor)
            tvPackage.setTextColor(subtitleColor)

            // Switch styling & state
            switchProxy.setLightMode(isLightContext)
            if (customSwitchColor != 0) {
                switchProxy.setActiveColor(customSwitchColor)
            } else {
                switchProxy.resetColors()
            }

            // Bind switch without trigger animation on recycle
            switchProxy.onCheckedChangeListener = null
            switchProxy.setChecked(app.isProxied, false)

            switchProxy.setOnCheckedChangeListener { _, isChecked ->
                if (app.isProxied != isChecked) {
                    app.isProxied = isChecked
                    onToggle(app, isChecked)
                }
            }

            row.setOnClickListener {
                switchProxy.toggle()
            }
        }
    }
}
