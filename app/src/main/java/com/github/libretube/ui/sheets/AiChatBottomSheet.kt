package com.github.libretube.ui.sheets

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import io.noties.markwon.Markwon
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.BottomSheetAiChatBinding
import com.github.libretube.helpers.AiHelper
import com.github.libretube.helpers.AiHelper.ChatMessage
import com.github.libretube.helpers.TranscriptHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.models.CommonPlayerViewModel
import kotlinx.coroutines.launch

/**
 * Bottom sheet for AI-powered video chat and summaries.
 * Uses OpenAI-compatible API to let users ask questions about the video
 * based on its transcript/captions.
 */
class AiChatBottomSheet(
    private val streams: Streams,
    private val videoId: String,
    private val onSeekTo: (Long) -> Unit
) : ExpandablePlayerSheet(R.layout.bottom_sheet_ai_chat) {

    private var _binding: BottomSheetAiChatBinding? = null
    private val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private lateinit var adapter: AiMessageAdapter
    private var isLoading = false
    private val markwon: Markwon by lazy { Markwon.create(requireContext()) }

    // Use ViewModel-backed state for persistence across open/close
    private val messages get() = commonPlayerViewModel.aiMessages
    private val messageViews get() = commonPlayerViewModel.aiMessageViews
    private var transcriptText: String?
        get() = commonPlayerViewModel.aiTranscriptText
        set(value) { commonPlayerViewModel.aiTranscriptText = value }
    private var transcriptLanguage: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = BottomSheetAiChatBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter = AiMessageAdapter { timestampMs ->
            onSeekTo(timestampMs)
        }
        binding.aiMessagesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.aiMessagesRecycler.adapter = adapter

        binding.aiCloseBtn.setOnClickListener { dismiss() }

        // Apply bottom system insets (navigation bar + keyboard) so input stays visible
        ViewCompat.setOnApplyWindowInsetsListener(binding.aiChatContainer) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, maxOf(navBar, ime))
            insets
        }

        if (!AiHelper.isConfigured()) {
            showNotConfigured()
            return
        }

        binding.aiChatContainer.visibility = View.VISIBLE
        binding.aiNotConfiguredContainer.visibility = View.GONE

        binding.aiSendBtn.setOnClickListener { sendUserMessage() }

        binding.aiSummarizeChip.setOnClickListener {
            if (!isLoading) requestSummary()
        }

        // Restore existing messages if this videoId matches, otherwise reset
        if (commonPlayerViewModel.aiVideoId == videoId && messages.isNotEmpty()) {
            adapter.notifyDataSetChanged()
            scrollToBottom()
            // Transcript already loaded
            binding.aiSummarizeChip.isEnabled = transcriptText != null
            binding.aiSummarizeChip.text = getString(R.string.ai_summarize)
        } else {
            // New video - clear previous state
            commonPlayerViewModel.clearAiState()
            commonPlayerViewModel.aiVideoId = videoId
            // Load transcript in background
            loadTranscript()
        }
    }

    override fun getSheetMaxHeightPx() = commonPlayerViewModel.maxSheetHeightPx

    override fun getDragHandle() = binding.dragHandle

    override fun getBottomSheet() = binding.standardBottomSheet

    private fun showNotConfigured() {
        binding.aiChatContainer.visibility = View.GONE
        binding.aiNotConfiguredContainer.visibility = View.VISIBLE
        binding.aiGoToSettingsBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
            dismiss()
        }
    }

    private fun loadTranscript() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.aiSummarizeChip.isEnabled = false

            val segments = TranscriptHelper.fetchTranscript(streams.subtitles)

            if (segments.isNullOrEmpty()) {
                val err = TranscriptHelper.lastError
                val msg = if (err != null) {
                    getString(R.string.ai_no_captions) + "\n[Debug: $err]"
                } else {
                    getString(R.string.ai_no_captions)
                }
                addAssistantMessage(msg)
                binding.aiSummarizeChip.isEnabled = false
                binding.aiSendBtn.isEnabled = false
                binding.aiInputEdittext.isEnabled = false
                return@launch
            }

            transcriptText = TranscriptHelper.truncateForContext(segments)
            transcriptLanguage = TranscriptHelper.fetchedLanguage
            commonPlayerViewModel.aiTranscriptLoaded = true
            binding.aiSummarizeChip.isEnabled = true
            binding.aiSummarizeChip.text = getString(R.string.ai_summarize)
        }
    }

    private fun requestSummary() {
        val transcript = transcriptText ?: return
        val title = streams.title

        addAssistantMessage(getString(R.string.ai_thinking))
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = AiHelper.summarize(transcript, title, streams.description, streams.uploader, transcriptLanguage)
            isLoading = false

            // Remove "thinking..." message
            removeLastMessage()

            if (result.isSuccess) {
                addAssistantMessage(result.getOrDefault(""))
            } else {
                addAssistantMessage(getString(R.string.ai_error) + " ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun sendUserMessage() {
        val text = binding.aiInputEdittext.text?.toString()?.trim() ?: ""
        if (text.isBlank() || isLoading) return

        // Dismiss keyboard
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.aiInputEdittext.windowToken, 0)

        // Capture history BEFORE adding the new message — only real past messages
        val history = messages
            .filter { it.role != "system" && it.content != getString(R.string.ai_thinking) }

        addUserMessage(text)
        binding.aiInputEdittext.text?.clear()

        addAssistantMessage(getString(R.string.ai_thinking))
        isLoading = true

        val transcript = transcriptText
        if (transcript == null) {
            removeLastMessage()
            addAssistantMessage(getString(R.string.ai_no_captions))
            isLoading = false
            return
        }

        val title = streams.title

        viewLifecycleOwner.lifecycleScope.launch {
            val result = AiHelper.ask(text, transcript, title, history, streams.description, streams.uploader, transcriptLanguage)
            isLoading = false

            removeLastMessage()

            if (result.isSuccess) {
                addAssistantMessage(result.getOrDefault(""))
            } else {
                addAssistantMessage(getString(R.string.ai_error) + " ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage("user", text))
        messageViews.add(true)
        adapter.notifyItemInserted(messageViews.size - 1)
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String) {
        messages.add(ChatMessage("assistant", text))
        messageViews.add(false)
        adapter.notifyItemInserted(messageViews.size - 1)
        scrollToBottom()
    }

    private fun removeLastMessage() {
        if (messages.isEmpty()) return
        messages.removeAt(messages.lastIndex)
        messageViews.removeAt(messageViews.lastIndex)
        adapter.notifyItemRemoved(messageViews.size)
    }

    private fun scrollToBottom() {
        binding.aiMessagesRecycler.post {
            binding.aiMessagesRecycler.scrollToPosition(messageViews.size - 1)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * RecyclerView adapter for chat messages.
     * Renders AI responses with clickable timestamps.
     */
    private inner class AiMessageAdapter(
        private val onTimestampClick: (Long) -> Unit
    ) : RecyclerView.Adapter<AiMessageAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.ai_message_text)
            val container: android.widget.LinearLayout = view.findViewById(R.id.ai_message_container)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.ai_message_item, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val isUser = messageViews[position]
            val message = messages[position]
            val context = holder.itemView.context
            val padding = 16f.dp().toInt()

            val shapeModel = com.google.android.material.shape.ShapeAppearanceModel.builder(
                context, 0, com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Medium
            ).build()

            if (isUser) {
                val spannable = formatMessageWithTimestamps(message.content, onTimestampClick)
                holder.text.text = spannable
                holder.text.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        holder.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer
                    )
                )
                holder.text.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                val containerParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.END
                )
                holder.container.layoutParams = containerParams
                holder.itemView.setPadding(padding, 6, padding, 6)
                holder.container.background = com.google.android.material.shape.MaterialShapeDrawable(shapeModel).apply {
                    setTint(com.google.android.material.color.MaterialColors.getColor(
                        holder.itemView, com.google.android.material.R.attr.colorPrimaryContainer
                    ))
                }
            } else {
                val markdownSpanned = markwon.toMarkdown(message.content)
                val withTimestamps = applyTimestampSpans(markdownSpanned, onTimestampClick)
                holder.text.text = withTimestamps
                holder.text.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        holder.itemView, com.google.android.material.R.attr.colorOnSurface
                    )
                )
                holder.text.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                val containerParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.START
                )
                holder.container.layoutParams = containerParams
                holder.itemView.setPadding(padding, 6, padding, 6)
                holder.container.background = com.google.android.material.shape.MaterialShapeDrawable(shapeModel).apply {
                    setTint(com.google.android.material.color.MaterialColors.getColor(
                        holder.itemView, com.google.android.material.R.attr.colorSurfaceContainerHighest
                    ))
                }
            }
            holder.text.movementMethod = LinkMovementMethod.getInstance()
        }

        override fun getItemCount(): Int = messageViews.size
    }

    companion object {
        private const val TAG = "AiChatBottomSheet"

        private fun Float.dp(): Float {
            return this * android.content.res.Resources.getSystem().displayMetrics.density
        }

        /**
         * Parses [MM:SS] timestamps in text and makes them clickable.
         */
        fun formatMessageWithTimestamps(
            text: String,
            onTimestampClick: (Long) -> Unit
        ): SpannableStringBuilder {
            val builder = SpannableStringBuilder(text)
            applyTimestampSpans(builder, onTimestampClick)
            return builder
        }

        /**
         * Applies clickable timestamp spans to any CharSequence containing timestamp patterns.
         * Handles [MM:SS], [MM:SS, MM:SS], and bare MM:SS formats.
         * Removes brackets and commas from display, keeping only MM:SS clickable.
         */
        fun applyTimestampSpans(
            text: CharSequence,
            onTimestampClick: (Long) -> Unit
        ): CharSequence {
            val original = text.toString()
            // Match bracketed groups containing one or more MM:SS timestamps
            val bracketPattern = Regex("\\[(\\d{1,2}:\\d{2}(?:\\s*,\\s*\\d{1,2}:\\d{2})*)\\]")
            // Match bare MM:SS not already inside brackets
            val barePattern = Regex("(?<![\\[,])\\b(\\d{1,2}):(\\d{2})\\b(?![\\],])")

            // Collect all timestamp occurrences with their positions
            data class TimestampMatch(val start: Int, val end: Int, val timestamps: List<Pair<Int, Int>>)
            val allMatches = mutableListOf<TimestampMatch>()

            // Find bracketed timestamps first
            for (match in bracketPattern.findAll(original)) {
                val inner = match.groupValues[1]
                val timePattern = Regex("(\\d{1,2}):(\\d{2})")
                val timestamps = timePattern.findAll(inner).map { 
                    it.groupValues[1].toInt() to it.groupValues[2].toInt()
                }.toList()
                allMatches.add(TimestampMatch(match.range.first, match.range.last + 1, timestamps))
            }

            // Find bare timestamps that aren't inside brackets
            for (match in barePattern.findAll(original)) {
                val start = match.range.first
                val end = match.range.last + 1
                // Skip if inside a bracketed match
                if (allMatches.any { start >= it.start && end <= it.end }) continue
                allMatches.add(TimestampMatch(start, end, listOf(match.groupValues[1].toInt() to match.groupValues[2].toInt())))
            }

            if (allMatches.isEmpty()) return text

            // Sort by position
            allMatches.sortBy { it.start }

            // Build new text with brackets removed, each timestamp as separate clickable MM:SS
            val sb = StringBuilder()
            val clickableRanges = mutableListOf<Pair<Int, Pair<Int, Int>>>() // (start in sb, (mins, secs))
            var pos = 0
            for (match in allMatches) {
                // Append text before this match
                sb.append(original, pos, match.start)
                // Append each timestamp as MM:SS with separator
                for ((index, ts) in match.timestamps.withIndex()) {
                    if (index > 0) sb.append(", ")
                    val mmssStart = sb.length
                    sb.append(ts.first.toString().padStart(2, '0'))
                    sb.append(":")
                    sb.append(ts.second.toString().padStart(2, '0'))
                    clickableRanges.add(mmssStart to ts)
                }
                pos = match.end
            }
            sb.append(original, pos, original.length)

            val builder = SpannableStringBuilder(sb)
            for ((rangeStart, ts) in clickableRanges) {
                val mins = ts.first.toLong()
                val secs = ts.second.toLong()
                val timestampMs = (mins * 60 + secs) * 1000
                val rangeEnd = rangeStart + String.format("%02d:%02d", mins, secs).length

                val span = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        onTimestampClick(timestampMs)
                    }
                }

                builder.setSpan(span, rangeStart, rangeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            return builder
        }
    }
}
