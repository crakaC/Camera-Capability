package com.crakac.encodingtest.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

typealias BindCallback<T> = (view: View, data: T, position: Int) -> Unit

class GenericListAdapter<T>(
    private val dataset: List<T>,
    private val itemLayoutId: Int? = null,
    private val itemViewFactory: (() -> View)? = null,
    private val onBind: BindCallback<T>
) : RecyclerView.Adapter<GenericListAdapter.GenericListViewHolder>() {

    class GenericListViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GenericListViewHolder(
        when {
            itemViewFactory != null -> itemViewFactory.invoke()
            itemLayoutId != null -> {
                LayoutInflater.from(parent.context).inflate(itemLayoutId, parent, false)
            }
            else -> {
                throw IllegalStateException("Either the layout ID or the view factory need to be non-null")
            }

        }
    )

    override fun onBindViewHolder(holder: GenericListViewHolder, position: Int) {
        if(position < 0 || position > dataset.size) return
        onBind(holder.view, dataset[position], position)
    }

    override fun getItemCount() = dataset.size
}