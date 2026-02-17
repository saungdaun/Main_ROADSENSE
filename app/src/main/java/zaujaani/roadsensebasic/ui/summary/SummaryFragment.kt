package zaujaani.roadsensebasic.ui.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.databinding.FragmentSummaryBinding

@AndroidEntryPoint
class SummaryFragment : Fragment() {

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SummaryViewModel by viewModels()

    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onItemClick = { item ->
                showSessionOptions(item.session)
            },
            onDetailClick = { item ->
                Toast.makeText(requireContext(), getString(R.string.detail_coming_soon, item.session.id), Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSessions()
        }
    }

    private fun observeViewModel() {
        viewModel.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }

        // Parameter isLoading tidak digunakan, tapi kita bisa mengabaikan dengan aman
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // Tidak perlu melakukan apa-apa, swipe refresh sudah menangani loading state
        }
    }

    private fun showSessionOptions(session: SurveySession) {
        val items = arrayOf(
            getString(R.string.view_details),
            getString(R.string.delete),
            getString(R.string.export_gpx)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.session_options))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(requireContext(), getString(R.string.detail_coming_soon, session.id), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        confirmDelete(session)
                    }
                    2 -> {
                        exportSession(session)
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(session: SurveySession) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_session))
            .setMessage(getString(R.string.delete_session_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteSession(session)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun exportSession(session: SurveySession) {
        viewModel.exportSessionToGpx(session.id) { file ->
            if (file != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.export_success))
                    .setMessage(getString(R.string.export_success_message, file.absolutePath))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } else {
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}