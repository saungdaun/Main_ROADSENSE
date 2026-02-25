package zaujaani.roadsensebasic.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.FragmentAboutBinding
import java.util.Calendar

/**
 * AboutFragment — Informasi aplikasi, developer, standar, dan tech stack.
 *
 * Ditampilkan via drawer navigation.
 * Tap kartu GitHub → buka browser ke repository.
 */
@AndroidEntryPoint
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val GITHUB_URL = "https://github.com/saungdaun/Main_ROADSENSE"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupVersionInfo()
        setupGithubLink()
    }

    private fun setupVersionInfo() {
        // Version dari BuildConfig (otomatis dari build.gradle)
        val versionName = try {
            BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            "1.0.0"
        }

        val versionCode = try {
            BuildConfig.VERSION_CODE
        } catch (e: Exception) {
            1
        }

        binding.tvVersion.text = "Version $versionName"
        binding.tvBuildDate.text = "Build $versionCode · ${Calendar.getInstance().get(Calendar.YEAR)}"

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvCopyright.text = "© $year saungdaun"
        binding.tvFooterCopyright.text = "© $year saungdaun · RoadSense Basic"
    }

    private fun setupGithubLink() {
        binding.cardGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                startActivity(intent)
            } catch (e: Exception) {
                // Browser tidak tersedia — fallback diam saja
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}