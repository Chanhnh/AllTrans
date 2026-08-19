/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package chanhnh.alltrans

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ModelManagerAdapter(
    private val models: MutableList<LanguageModelItem>,
    private val onDownloadClick: (LanguageModelItem) -> Unit,
    private val onDeleteClick: (LanguageModelItem) -> Unit
) : RecyclerView.Adapter<ModelManagerAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Create a new view, which defines the UI of the list item
        // Ensure you have a layout file named 'item_model_manager.xml'
        // This will be created in a subsequent step if it doesn't exist.
        // For now, the worker can assume it will exist or use a placeholder if absolutely necessary
        // for the subtask to complete, but the primary goal is the adapter structure.
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_manager, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.languageNameTextView.text = "${model.displayName} (${model.languageCode})"

        when {
            model.isDownloading -> {
                holder.statusTextView.text = holder.itemView.context.getString(R.string.status_downloading)
                holder.downloadButton.visibility = View.GONE
                holder.deleteButton.visibility = View.GONE
                holder.progressBar.visibility = View.VISIBLE
            }
            model.isDownloaded -> {
                holder.statusTextView.text = holder.itemView.context.getString(R.string.status_downloaded)
                holder.downloadButton.visibility = View.GONE
                holder.deleteButton.visibility = View.VISIBLE
                holder.progressBar.visibility = View.GONE
            }
            else -> { // Not downloaded, not downloading
                holder.statusTextView.text = holder.itemView.context.getString(R.string.status_not_downloaded)
                holder.downloadButton.visibility = View.VISIBLE
                holder.deleteButton.visibility = View.GONE
                holder.progressBar.visibility = View.GONE
            }
        }

        holder.downloadButton.setOnClickListener { onDownloadClick(model) }
        holder.deleteButton.setOnClickListener { onDeleteClick(model) }
    }

    override fun getItemCount() = models.size

    fun updateModelStatus(languageCode: String, isDownloaded: Boolean, isDownloading: Boolean) {
        val index = models.indexOfFirst { it.languageCode == languageCode }
        if (index != -1) {
            models[index].isDownloaded = isDownloaded
            models[index].isDownloading = isDownloading
            notifyItemChanged(index)
        }
    }

    fun updateModels(newModels: List<LanguageModelItem>) {
        models.clear()
        models.addAll(newModels)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val languageNameTextView: TextView = view.findViewById(R.id.language_name_textview)
        val statusTextView: TextView = view.findViewById(R.id.model_status_textview)
        val downloadButton: Button = view.findViewById(R.id.download_model_button)
        val deleteButton: Button = view.findViewById(R.id.delete_model_button)
        val progressBar: ProgressBar = view.findViewById(R.id.model_item_progress_bar)
    }
}
