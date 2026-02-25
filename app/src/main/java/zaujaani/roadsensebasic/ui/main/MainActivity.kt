package zaujaani.roadsensebasic.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.ActivityMainBinding

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle

    // ── Permissions ──────────────────────────────────────────────────────

    private val requiredPermissions: Array<String>
        get() {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return permissions.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val denied = permissions.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some permissions are denied. App may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        setupNavigation()
        checkAndRequestPermissions()
        setupBackPressedHandler()
    }

    // ── Navigation ───────────────────────────────────────────────────────

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    as NavHostFragment
        val navController = navHostFragment.navController

        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.topAppBar,
            R.string.app_name,
            R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Setup drawer — intercept item yang butuh handling khusus
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawers()

            when (menuItem.itemId) {

                // "Analisis Foto AI" di drawer → arahkan ke Summary dulu.
                // User pilih session di Summary → long-press → Analisis Foto AI.
                // Tidak bisa langsung ke PhotoAnalysisFragment karena butuh sessionId.
                R.id.action_photoAnalysisFragment -> {
                    navController.navigate(R.id.summaryFragment)
                    Snackbar.make(
                        binding.root,
                        "Pilih sesi survey → tahan → Analisis Foto AI",
                        Snackbar.LENGTH_LONG
                    ).show()
                    true
                }

                // Semua item lain dihandle NavigationUI biasa
                else -> NavigationUI.onNavDestinationSelected(menuItem, navController)
            }
        }

        // Update judul Toolbar saat destinasi berubah
        navController.addOnDestinationChangedListener { _, destination, _ ->
            supportActionBar?.title = destination.label
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    // ── Back Press ───────────────────────────────────────────────────────

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(binding.navigationView)) {
                binding.drawerLayout.closeDrawer(binding.navigationView)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }
}