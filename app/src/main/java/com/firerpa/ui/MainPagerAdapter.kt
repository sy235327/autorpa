package com.firerpa.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.firerpa.databinding.PageConfigBinding
import com.firerpa.databinding.PageHomeBinding
import com.firerpa.databinding.PageLicenseBinding
import com.firerpa.databinding.PageLogBinding

class MainPagerAdapter(
    private val onConfigPageBound: (PageConfigBinding) -> Unit = {},
    private val onLogPageBound: (PageLogBinding) -> Unit = {},
    private val onLicensePageBound: (PageLicenseBinding) -> Unit = {},
    private val onHomePageBound: (PageHomeBinding) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val pageHome = 0
        const val pageConfig = 1
        const val pageLicense = 2
        const val pageLog = 3
    }

    var homeBinding: PageHomeBinding? = null
        private set

    var configBinding: PageConfigBinding? = null
        private set

    var licenseBinding: PageLicenseBinding? = null
        private set

    var logBinding: PageLogBinding? = null
        private set

    override fun getItemCount(): Int = 4

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            pageHome -> {
                val binding = PageHomeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                homeBinding = binding
                onHomePageBound(binding)
                HomeHolder(binding)
            }
            pageConfig -> {
                val binding = PageConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                configBinding = binding
                onConfigPageBound(binding)
                ConfigHolder(binding)
            }
            pageLicense -> {
                val binding = PageLicenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                licenseBinding = binding
                onLicensePageBound(binding)
                LicenseHolder(binding)
            }
            else -> {
                val binding = PageLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                logBinding = binding
                onLogPageBound(binding)
                LogHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

    class HomeHolder(val binding: PageHomeBinding) : RecyclerView.ViewHolder(binding.root)

    class ConfigHolder(val binding: PageConfigBinding) : RecyclerView.ViewHolder(binding.root)

    class LicenseHolder(val binding: PageLicenseBinding) : RecyclerView.ViewHolder(binding.root)

    class LogHolder(val binding: PageLogBinding) : RecyclerView.ViewHolder(binding.root)
}
