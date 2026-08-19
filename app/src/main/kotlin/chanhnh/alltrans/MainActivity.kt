package chanhnh.alltrans

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// Interface para o Fragmento pesquisável
interface SearchableFragment {
    fun updateSearchQuery(query: String?)
}

// ViewModel para gerenciar o estado da aplicação
class MainViewModel : ViewModel() {
    private val _activeSearchQuery = MutableStateFlow<String?>(null)
    val activeSearchQuery: StateFlow<String?> = _activeSearchQuery

    private val _currentFragmentType = MutableStateFlow(FragmentType.APPS) // Padrão para APPS
    val currentFragmentType: StateFlow<FragmentType> = _currentFragmentType

    fun updateActiveSearchQuery(query: String?) {
        if (_activeSearchQuery.value != query) {
            _activeSearchQuery.value = query
            Utils.debugLog("ViewModel: Updated activeSearchQuery to '$query'")
        }
    }

    fun updateCurrentFragmentType(type: FragmentType) {
        if (_currentFragmentType.value != type) {
            _currentFragmentType.value = type
            Utils.debugLog("ViewModel: Updated currentFragmentType to $type")
            if (type != FragmentType.APPS) {
                clearActiveSearchQuery() // Limpa a pesquisa ao sair da aba de apps
            }
        }
    }

    fun clearActiveSearchQuery() {
        if (_activeSearchQuery.value != null) {
            _activeSearchQuery.value = null
            Utils.debugLog("ViewModel: Cleared activeSearchQuery")
        }
    }

    enum class FragmentType {
        APPS, SETTINGS, INSTRUCTIONS
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var appBarLayout: AppBarLayout
    var searchView: SearchView? = null // public para AppListFragment poder limpar o foco
    private var searchMenuItem: MenuItem? = null

    private val viewModel: MainViewModel by viewModels()

    // Usado pelo AppListFragment para obter a query que deve ser restaurada nele
    fun getCurrentRestorableQuery(): String? {
        return viewModel.activeSearchQuery.value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupDebugMode()
        applyWindowInsets()
        setupBottomNavigation()
        setupOnBackPressed()

        // Observar mudanças no tipo de fragmento para atualizar a UI (título e menu)
        viewModel.currentFragmentType
            .onEach { fragmentType ->
                Utils.debugLog("MainActivity: CurrentFragmentType changed to $fragmentType, updating UI.")
                updateToolbarTitleForType(fragmentType)
                invalidateOptionsMenu() // Sempre invalida para mostrar/esconder o menu de pesquisa
            }
            .launchIn(lifecycleScope)

        if (savedInstanceState == null) {
            // Na primeira carga, navega para o tipo de fragmento inicial da ViewModel (APPS por padrão)
            // e garante que o item da BottomNav seja selecionado.
            navigateToFragmentType(viewModel.currentFragmentType.value, true)
        }
        // Se savedInstanceState != null, a ViewModel já restaurou seu estado (activeSearchQuery, currentFragmentType).
        // O observador de currentFragmentType acima e a lógica em onResume do fragmento
        // (que também chama invalidateOptionsMenu) cuidarão de restaurar a UI.
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.app_bar_layout)
        setSupportActionBar(toolbar)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
    }

    private fun setupDebugMode() {
        val settings = getSharedPreferences("AllTransPref", MODE_PRIVATE)
        Utils.Debug = settings.getBoolean("Debug", false)
    }

    private fun updateToolbarTitleForType(fragmentType: MainViewModel.FragmentType) {
        val titleRes = when (fragmentType) {
            MainViewModel.FragmentType.APPS -> R.string.apps_to_translate
            MainViewModel.FragmentType.SETTINGS -> R.string.global_settings
            MainViewModel.FragmentType.INSTRUCTIONS -> R.string.instructions
        }
        supportActionBar?.title = getString(titleRes)
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            val targetType = when (item.itemId) {
                R.id.navigation_apps -> MainViewModel.FragmentType.APPS
                R.id.navigation_settings -> MainViewModel.FragmentType.SETTINGS
                R.id.navigation_instructions -> MainViewModel.FragmentType.INSTRUCTIONS
                else -> viewModel.currentFragmentType.value // Não mudar se for um item desconhecido
            }
            // Se o tipo de fragmento alvo é diferente do atual, navegue.
            // Isso evita recarregar o mesmo fragmento se o usuário clicar na aba já selecionada.
            if (viewModel.currentFragmentType.value != targetType) {
                navigateToFragmentType(targetType, false)
            }
            true // Sempre retorna true para indicar que o evento foi consumido
        }

        // Sincronizar o item selecionado na BottomNav se a ViewModel mudar o tipo de fragmento
        // (útil para o botão voltar ou navegação programática para uma aba específica)
        viewModel.currentFragmentType
            .onEach { fragmentType ->
                val itemId = when (fragmentType) {
                    MainViewModel.FragmentType.APPS -> R.id.navigation_apps
                    MainViewModel.FragmentType.SETTINGS -> R.id.navigation_settings
                    MainViewModel.FragmentType.INSTRUCTIONS -> R.id.navigation_instructions
                }
                if (bottomNavigationView.selectedItemId != itemId) {
                    bottomNavigationView.selectedItemId = itemId
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun navigateToFragmentType(type: MainViewModel.FragmentType, setSelectedItemInBottomNav: Boolean) {
        Utils.debugLog("MainActivity: Navigating to fragment type: $type. Current ViewModel type: ${viewModel.currentFragmentType.value}")

        // Atualiza o ViewModel ANTES de tentar colapsar a SearchView ou trocar o fragmento
        val previousFragmentType = viewModel.currentFragmentType.value
        viewModel.updateCurrentFragmentType(type)

        val fragment = when (type) {
            MainViewModel.FragmentType.APPS -> AppListFragment()
            MainViewModel.FragmentType.SETTINGS -> GlobalPreferencesFragment()
            MainViewModel.FragmentType.INSTRUCTIONS -> InstructionsFragment()
        }

        // Se estava na tela de APPS e está saindo dela, e a searchview está expandida, colapse-a.
        // A ViewModel já cuida de limpar a activeSearchQuery.
        if (previousFragmentType == MainViewModel.FragmentType.APPS && type != MainViewModel.FragmentType.APPS) {
            searchMenuItem?.let {
                if (it.isActionViewExpanded) {
                    Utils.debugLog("MainActivity: Collapsing SearchView as navigating away from APPS fragment.")
                    it.collapseActionView() // O listener onMenuItemActionCollapse será chamado
                }
            }
        }

        replaceFragment(fragment)

        if (setSelectedItemInBottomNav) {
            val itemId = when (type) {
                MainViewModel.FragmentType.APPS -> R.id.navigation_apps
                MainViewModel.FragmentType.SETTINGS -> R.id.navigation_settings
                MainViewModel.FragmentType.INSTRUCTIONS -> R.id.navigation_instructions
            }
            if (bottomNavigationView.selectedItemId != itemId) {
                bottomNavigationView.selectedItemId = itemId
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Utils.debugLog("MainActivity: onCreateOptionsMenu called. Current fragment type: ${viewModel.currentFragmentType.value}")
        if (viewModel.currentFragmentType.value == MainViewModel.FragmentType.APPS) {
            menuInflater.inflate(R.menu.main_activity_menu, menu)
            MenuCompat.setGroupDividerEnabled(menu, true)

            searchMenuItem = menu.findItem(R.id.action_search_main)
            searchView = searchMenuItem?.actionView as? SearchView

            searchView?.queryHint = getString(R.string.search)
            searchView?.maxWidth = Integer.MAX_VALUE

            val queryToRestore = viewModel.activeSearchQuery.value
            Utils.debugLog("MainActivity: Restoring query to SearchView: '$queryToRestore'")
            if (!queryToRestore.isNullOrEmpty()) {
                // Adicionar um pequeno delay pode ajudar se a SearchView não estiver pronta para aceitar a query imediatamente
                searchMenuItem?.expandActionView()
                searchView?.setQuery(queryToRestore, false) // false = não submeter
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    Utils.debugLog("MainActivity: SearchView submitted: '$query'")
                    viewModel.updateActiveSearchQuery(query) // Atualiza ViewModel
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.toReplace)
                    if (currentFragment is SearchableFragment) {
                        currentFragment.updateSearchQuery(query)
                    }
                    searchView?.clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    Utils.debugLog("MainActivity: SearchView text changed: '$newText'")
                    viewModel.updateActiveSearchQuery(newText) // Atualiza ViewModel em tempo real
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.toReplace)
                    if (currentFragment is SearchableFragment) {
                        currentFragment.updateSearchQuery(newText)
                    }
                    return true
                }
            })

            searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    Utils.debugLog("MainActivity: SearchView expanded.")
                    // Se houver uma query na ViewModel, preencha a SearchView ao expandir
                    val queryToRestoreOnExpand = viewModel.activeSearchQuery.value
                    if (!queryToRestoreOnExpand.isNullOrEmpty()) {
                        searchView?.setQuery(queryToRestoreOnExpand, false)
                    }
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    Utils.debugLog("MainActivity: SearchView collapsed.")
                    viewModel.clearActiveSearchQuery() // Limpa a query na ViewModel
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.toReplace)
                    if (currentFragment is SearchableFragment) {
                        currentFragment.updateSearchQuery(null) // Notifica o fragmento para limpar o filtro
                    }
                    return true
                }
            })
        } else {
            menu.clear() // Limpa o menu (remove o ícone de pesquisa)
            searchView = null
            searchMenuItem = null
        }
        return true
    }

    private fun applyWindowInsets() {
        val containerView = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main_container)
        ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, windowInsets ->
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBarLayout.updatePadding(
                top = systemBarInsets.top,
                left = systemBarInsets.left,
                right = systemBarInsets.right
            )
            bottomNavigationView.updatePadding(
                bottom = systemBarInsets.bottom,
                left = systemBarInsets.left,
                right = systemBarInsets.right
            )
            findViewById<FrameLayout>(R.id.toReplace).updatePadding(
                left = systemBarInsets.left,
                right = systemBarInsets.right
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchMenuItem?.isActionViewExpanded == true) {
                    Utils.debugLog("MainActivity: Back pressed - collapsing SearchView.")
                    searchMenuItem?.collapseActionView() // O listener cuidará de limpar a query
                } else if (viewModel.currentFragmentType.value != MainViewModel.FragmentType.APPS) {
                    Utils.debugLog("MainActivity: Back pressed - navigating to APPS fragment.")
                    navigateToFragmentType(MainViewModel.FragmentType.APPS, true) // true para selecionar o item na BottomNav
                } else {
                    Utils.debugLog("MainActivity: Back pressed - allowing super.onBackPressed.")
                    if (isEnabled) { // Necessário para o callback do OnBackPressedDispatcher
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed() // Chama o comportamento padrão
                    }
                }
            }
        })
    }

    private fun replaceFragment(fragment: Fragment) {
        Utils.debugLog("MainActivity: Replacing fragment with ${fragment.javaClass.simpleName}")
        supportFragmentManager.beginTransaction()
            .replace(R.id.toReplace, fragment)
            .commit()
    }
}