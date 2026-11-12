package com.github.libretube.ui.activities

import android.os.Bundle
import com.github.libretube.databinding.ActivityHelpBinding
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView

class HelpActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupCard(binding.faq, FAQ_URL)
        setupCard(binding.report, ISSUES_URL)
    }

    private fun setupCard(card: MaterialCardView, link: String) {
        card.setOnClickListener {
            IntentHelper.openLinkFromHref(this, supportFragmentManager, link)
        }
    }

    companion object {
        private const val FAQ_URL = "https://libretube.dev/#faq"
        private const val ISSUES_URL = "${AboutActivity.GITHUB_URL}/issues"
    }
}
