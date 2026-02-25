package zaujaani.roadsensebasic.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
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

        binding.tvVersion.text = getString(R.string.version_format, versionName)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvBuildDate.text = getString(R.string.build_format, versionCode, year)

        // Copyright profesional
        binding.tvCopyright.text = getString(R.string.copyright_format, year)

        // Footer (sudah pakai string resource di XML, tapi kita set ulang jika perlu)
        binding.tvFooterCopyright.text = getString(R.string.about_footer)
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