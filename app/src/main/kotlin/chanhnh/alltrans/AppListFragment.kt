package chanhnh.alltrans

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Filter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import kotlin.Comparator

class AppListFragment : Fragment(), SearchableFragment {

    private var listview: ListView? = null
    private var loadingIndicator: CircularProgressIndicator? = null
    private var adapter: StableArrayAdapter? = null

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Utils.debugLog("AppListFragment: onCreateView")
        return inflater.inflate(R.layout.apps_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.debugLog("AppListFragment: onViewCreated. Initial ViewModel query: '${mainViewModel.activeSearchQuery.value}'")
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        listview = view.findViewById(R.id.AppsList)

        // Companion object property, can be accessed via class name
        AppListFragment.settings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        listview?.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listview?.isFastScrollEnabled = true
        listview?.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val curApp = adapter?.getItem(position)
            if (curApp != null) {
                (activity as? MainActivity)?.searchView?.clearFocus()
                val localPreferenceFragment = LocalPreferenceFragment()
                localPreferenceFragment.applicationInfo = curApp
                parentFragmentManager.beginTransaction()
                    .replace(R.id.toReplace, localPreferenceFragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                Log.e("AppListFragment", "Clicked item at position $position is null in adapter.")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.activeSearchQuery.collectLatest { query ->
                Utils.debugLog("AppListFragment: Observed query from ViewModel: '$query'. Applying to adapter.")
                adapter?.filter?.filter(query)
            }
        }
        loadPackagesAndSetupAdapter()
    }

    override fun onResume() {
        super.onResume()
        Utils.debugLog("AppListFragment: onResume.")
        mainViewModel.updateCurrentFragmentType(MainViewModel.FragmentType.APPS)
    }

    override fun updateSearchQuery(query: String?) {
        Utils.debugLog("AppListFragment: updateSearchQuery (from SearchableFragment interface) called with: '$query'. ViewModel's query is '${mainViewModel.activeSearchQuery.value}'")
        // ViewModel is the source of truth, observer should handle it.
    }

    private fun loadPackagesAndSetupAdapter() {
        val initialQueryFromViewModel = mainViewModel.activeSearchQuery.value
        Utils.debugLog("AppListFragment: loadPackagesAndSetupAdapter. Initial filter from ViewModel: '$initialQueryFromViewModel'")
        loadingIndicator?.isVisible = true
        listview?.isVisible = false

        lifecycleScope.launch(Dispatchers.Main) {
            val freshLoadedPackages = withContext(Dispatchers.IO) {
                Utils.debugLog("AppListFragment: Loading packages in IO thread...")
                val pm = requireContext().packageManager
                val packages = getInstalledApplications(requireContext())
                packages.sortWith(Comparator { a, b ->
                    val aEnabled = settings?.contains(a.packageName) == true
                    val bEnabled = settings?.contains(b.packageName) == true
                    if (aEnabled && !bEnabled) return@Comparator -1
                    if (!aEnabled && bEnabled) return@Comparator 1
                    val labelA = pm.getApplicationLabel(a).toString().lowercase(Locale.getDefault())
                    val labelB = pm.getApplicationLabel(b).toString().lowercase(Locale.getDefault())
                    labelA.compareTo(labelB)
                })
                Utils.debugLog("AppListFragment: Loaded ${packages.size} packages in IO thread.")
                packages
            }

            if (isAdded) { // Check if fragment is still added
                Utils.debugLog("AppListFragment: Packages loaded, creating/updating adapter with ${freshLoadedPackages.size} items.")
                adapter = StableArrayAdapter(requireContext(), freshLoadedPackages)
                listview?.adapter = adapter

                Utils.debugLog("AppListFragment: Applying initial ViewModel filter '$initialQueryFromViewModel' after new adapter set.")
                adapter?.filter?.filter(initialQueryFromViewModel)

                listview?.isVisible = true
            }
            loadingIndicator?.isVisible = false
            Utils.debugLog("AppListFragment: loadPackagesAndSetupAdapter finished.")
        }
    }

    internal class ViewHolder {
        var textView: TextView? = null
        var textView2: TextView? = null
        var imageView: ImageView? = null
        var checkBox: CheckBox? = null
    }

    private inner class StableArrayAdapter(
        context: Context,
        initialPackages: List<ApplicationInfo>
    ) : ArrayAdapter<ApplicationInfo>(context, R.layout.list_item, initialPackages) {

        // originalValues is modified by updateOriginalDataAndFilter
        private var originalValues: List<ApplicationInfo> = ArrayList(initialPackages)
        private val pm: PackageManager = context.packageManager
        private val inflater: LayoutInflater = LayoutInflater.from(context)

        fun updateOriginalDataAndFilter(newPackages: List<ApplicationInfo>, currentQuery: String?) {
            Utils.debugLog("StableArrayAdapter: updateOriginalDataAndFilter with ${newPackages.size} packages. Query: '$currentQuery'")
            this.originalValues = ArrayList(newPackages) // Re-assigning originalValues
            this.filter.filter(currentQuery)
        }

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    Utils.debugLog("StableArrayAdapter: performFiltering constraint: '$constraint', originalValues size: ${originalValues.size}")
                    val results = FilterResults()
                    val valuesToFilterFrom = ArrayList(originalValues) // Create a copy for filtering
                    val filterString = constraint?.toString()?.lowercase(Locale.getDefault())

                    if (filterString.isNullOrEmpty()) {
                        results.values = valuesToFilterFrom
                        results.count = valuesToFilterFrom.size
                    } else {
                        val filteredList = ArrayList<ApplicationInfo>() // Explicit type for clarity
                        for (appInfo in valuesToFilterFrom) {
                            val appName = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
                            val packageName = appInfo.packageName.lowercase(Locale.getDefault())
                            if (appName.contains(filterString) || packageName.contains(filterString)) {
                                filteredList.add(appInfo)
                            }
                        }
                        results.values = filteredList
                        results.count = filteredList.size
                    }
                    Utils.debugLog("StableArrayAdapter: performFiltering results count: ${results.count}")
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    if (results?.values != null) {
                        try {
                            addAll(results.values as List<ApplicationInfo>)
                        } catch (e: ClassCastException) {
                            Log.e("AppListFragment", "Error casting filter results", e)
                            // Fallback to originalValues if constraint is empty, otherwise it might show an empty list on error
                            if (constraint.isNullOrEmpty()) addAll(ArrayList(originalValues))
                        }
                    } else if (constraint.isNullOrEmpty()) { // Ensure list is repopulated if results are null and constraint is empty
                        addAll(ArrayList(originalValues))
                    }
                    notifyDataSetChanged() // Ensure UI updates
                    Utils.debugLog("StableArrayAdapter: publishResults finished. Adapter count: $count")
                }
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val currentView: View // Use val for the final assigned view
            val viewHolder: ViewHolder

            if (convertView == null) {
                currentView = inflater.inflate(R.layout.list_item, parent, false)
                viewHolder = ViewHolder()
                viewHolder.textView = currentView.findViewById(R.id.firstLine)
                viewHolder.textView2 = currentView.findViewById(R.id.secondLine)
                viewHolder.imageView = currentView.findViewById(R.id.icon)
                viewHolder.checkBox = currentView.findViewById(R.id.checkBox)
                currentView.tag = viewHolder
            } else {
                currentView = convertView
                viewHolder = currentView.tag as ViewHolder
            }

            val currentAppInfo = getItem(position)

            if (currentAppInfo != null) {
                val packageName = currentAppInfo.packageName ?: ""
                val label = pm.getApplicationLabel(currentAppInfo).toString()
                val icon = pm.getApplicationIcon(currentAppInfo)

                viewHolder.textView?.text = label
                viewHolder.textView?.isSelected = true // for marquee
                viewHolder.textView2?.text = packageName
                viewHolder.textView2?.isSelected = true // for marquee
                viewHolder.imageView?.setImageDrawable(icon)

                viewHolder.checkBox?.tag = currentAppInfo // Store app info for the click listener
                viewHolder.checkBox?.isChecked = settings?.contains(packageName) == true
                viewHolder.checkBox?.setOnClickListener { v ->
                    val checkBox = v as CheckBox
                    val appInfoFromTag = checkBox.tag as? ApplicationInfo ?: return@setOnClickListener
                    val pkgName = appInfoFromTag.packageName ?: return@setOnClickListener
                    Utils.debugLog("CheckBox clicked! $pkgName")

                    val globalEditor = settings?.edit()
                    val localSettings = requireActivity().getSharedPreferences(pkgName, Context.MODE_PRIVATE)
                    val localEditor = localSettings?.edit()

                    if (checkBox.isChecked) {
                        globalEditor?.putBoolean(pkgName, true)
                        localEditor?.putBoolean("LocalEnabled", true)
                    } else {
                        globalEditor?.remove(pkgName)
                        localEditor?.putBoolean("LocalEnabled", false)
                    }
                    globalEditor?.apply()
                    localEditor?.apply()

                    // Limpar cache de preferências para forçar recarregamento
                    PreferenceManager.clearCache(pkgName)
                    Utils.debugLog("Cleared preference cache for $pkgName due to setting change")
                }
            } else {
                // Clear views if currentAppInfo is null (e.g. during filtering)
                viewHolder.textView?.text = ""
                viewHolder.textView2?.text = ""
                viewHolder.imageView?.setImageDrawable(null)
                viewHolder.checkBox?.tag = null
                viewHolder.checkBox?.isChecked = false
                viewHolder.checkBox?.setOnClickListener(null)
            }
            return currentView
        }
    }

    private fun getInstalledApplications(context: Context): MutableList<ApplicationInfo> {
        val pm = context.packageManager
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val launcherApps = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .toMutableList()

            if (launcherApps.isNotEmpty()) {
                return launcherApps
            }
        } catch (e: Exception) {
            Log.w("AppListFragment", "queryIntentActivities failed, trying getInstalledApplications.", e)
        }

        try {
            return pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .toMutableList()
        } catch (e: Exception) {
            Log.w("AppListFragment", "getInstalledApplications failed, using fallback.", e)
        }

        // Fallback method
        val result: MutableList<ApplicationInfo> = ArrayList() // Explicit type for clarity
        var bufferedReader: BufferedReader? = null
        try {
            val process = Runtime.getRuntime().exec("pm list packages")
            bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                val currentLine = line // Use a val for the current line
                val packageName = currentLine?.substringAfter("package:", "")?.trim()
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                        result.add(applicationInfo)
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Ignored: package listed but not found, could be uninstalling
                    } catch (e: Exception) {
                        Log.e("AppListFragment", "Error getting app info during fallback for $packageName", e)
                    }
                }
            }
            process.waitFor() // Wait for the process to complete
        } catch (e: Throwable) { // Catch Throwable for broader error handling including InterruptedException
            Log.e("AppListFragment", "Error during fallback getInstalledApplications with 'pm list packages'", e)
        } finally {
            try {
                bufferedReader?.close()
            } catch (e: IOException) {
                // Ignored
            }
        }
        Log.i("AppListFragment", "Fallback 'pm list packages' loaded ${result.size} packages.")
        return result
    }

    companion object {
        // settings is accessed and potentially modified by multiple instances if not careful,
        // but here it's likely a single AppListFragment. It's being assigned in onViewCreated.
        // Making it truly static and initialized once might be better if it's meant to be a singleton preference access.
        // However, for fragment-specific preferences that are re-read, this is okay.
        // For "AllTransPref", it's a global preference, so a static field is fine.
        var settings: SharedPreferences? = null
    }
}
