package com.palash.librewolfandroid

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * GeckoView prompts used by ordinary web content. Every prompt is resolved
 * explicitly so JavaScript dialogs, HTTP authentication and file inputs cannot
 * leave a page waiting forever or terminate the browser through an unresolved
 * GeckoResult.
 */
class BrowserPromptDelegate(
    private val activity: AppCompatActivity,
    private val launchFilePicker: (Intent) -> Unit,
    private val launchCamera: (android.net.Uri) -> Unit,
    private val requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    private val launchShare: (title: String?, text: String?, uri: String?) -> Unit,
) : GeckoSession.PromptDelegate {

    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingCameraResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingCameraPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingCameraUri: android.net.Uri? = null

    private fun unavailable(prompt: GeckoSession.PromptDelegate.BasePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun inputContainer(message: String?): Pair<android.view.View, LinearLayout> {
        val scroll = android.widget.ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        scroll.addView(container)
        if (!message.isNullOrEmpty()) {
            container.addView(
                android.widget.TextView(activity).apply {
                    text = message
                    setPadding(0, 0, 0, dp(12))
                },
            )
        }
        return scroll to container
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val (view, container) = inputContainer(prompt.message)
        val input = EditText(activity).apply {
            setText(prompt.defaultValue)
            setSelection(text.length)
            setSingleLine(false)
            maxLines = 4
        }
        container.addView(input)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.complete(prompt.confirm(input.text.toString()))
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.before_unload_title)
            .setMessage(R.string.before_unload_message)
            .setPositiveButton(R.string.before_unload_leave) { _, _ ->
                result.complete(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(R.string.before_unload_stay) { _, _ ->
                result.complete(prompt.confirm(AllowOrDeny.DENY))
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.repost_title)
            .setMessage(R.string.repost_message)
            .setPositiveButton(R.string.repost_resend) { _, _ ->
                result.complete(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.complete(prompt.confirm(AllowOrDeny.DENY))
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AuthPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val (view, container) = inputContainer(prompt.message)
        val onlyPassword = prompt.authOptions.flags and
            GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val username = if (onlyPassword) null else EditText(activity).apply {
            hint = activity.getString(R.string.username)
            setText(prompt.authOptions.username)
            setSingleLine(true)
        }
        username?.let(container::addView)
        val password = EditText(activity).apply {
            hint = activity.getString(R.string.password)
            setText(prompt.authOptions.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(password)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.authentication_required))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val response = if (onlyPassword) {
                    prompt.confirm(password.text.toString())
                } else {
                    prompt.confirm(username?.text?.toString().orEmpty(), password.text.toString())
                }
                result.complete(response)
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        if (pendingFilePrompt != null || pendingCameraPrompt != null) return unavailable(prompt)

        val intent = Intent(if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
            Intent.ACTION_OPEN_DOCUMENT_TREE
        } else {
            Intent.ACTION_OPEN_DOCUMENT
        }).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val mimeTypes = prompt.mimeTypes?.filter { it.isNotBlank() }.orEmpty()
            val commonType = mimeTypes.firstOrNull { !it.contains(';') && !it.contains(',') }
            type = commonType ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }

        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        if (prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE &&
            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE
        ) {
            pendingCameraPrompt = prompt
            pendingCameraResult = result
            var sourceChosen = false
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.file_upload_source)
                .setItems(arrayOf(activity.getString(R.string.take_photo), activity.getString(R.string.choose_file))) { _, which ->
                    sourceChosen = true
                    if (which == 0) requestCameraPermission { granted ->
                        if (granted) launchCameraPrompt(prompt, result) else beginFilePicker(prompt, result, intent)
                    } else {
                        beginFilePicker(prompt, result, intent)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnDismissListener {
                // Selecting a source dismisses this chooser before the async
                // permission/camera result arrives; only an actual source-dialog
                // cancellation should complete the Gecko prompt.
                if (!sourceChosen && !prompt.isComplete) {
                    pendingCameraPrompt = null
                    pendingCameraResult = null
                    result.complete(prompt.dismiss())
                }
            }
            dialog.show()
        } else {
            beginFilePicker(prompt, result, intent)
        }
        return result
    }

    private fun beginFilePicker(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
        result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        intent: Intent,
    ) {
        pendingCameraPrompt = null
        pendingCameraResult = null
        pendingCameraUri = null
        pendingFilePrompt = prompt
        pendingFileResult = result
        try {
            launchFilePicker(intent)
        } catch (_: Exception) {
            pendingFilePrompt = null
            pendingFileResult = null
            result.complete(prompt.dismiss())
        }
    }

    private fun launchCameraPrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
        result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) {
        try {
            val file = java.io.File.createTempFile("librewolf_upload_", ".jpg", activity.cacheDir)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
            pendingCameraUri = uri
            launchCamera(uri)
        } catch (_: Exception) {
            pendingCameraPrompt = null
            pendingCameraResult = null
            pendingCameraUri = null
            result.complete(prompt.dismiss())
        }
    }

    fun onCameraResult(success: Boolean) {
        val prompt = pendingCameraPrompt ?: return
        val result = pendingCameraResult ?: return
        val uri = pendingCameraUri
        pendingCameraPrompt = null
        pendingCameraResult = null
        pendingCameraUri = null
        if (prompt.isComplete) return
        if (success && uri != null) result.complete(prompt.confirm(activity, uri)) else result.complete(prompt.dismiss())
    }

    fun onFilePickerResult(resultCode: Int, data: Intent?) {
        val prompt = pendingFilePrompt ?: return
        val result = pendingFileResult ?: return
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt.isComplete) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            result.complete(prompt.dismiss())
            return
        }

        val uris = buildList {
            data.clipData?.let { clip: ClipData ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
            data.data?.let(::add)
        }.distinct()
        if (uris.isEmpty()) {
            result.complete(prompt.dismiss())
        } else if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
            result.complete(prompt.confirm(activity, uris.toTypedArray()))
        } else {
            result.complete(prompt.confirm(activity, uris.first()))
        }
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ChoicePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val choices = flattenChoices(prompt.choices)
        if (choices.isEmpty()) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val selected = choices.filter { it.selected }.toMutableSet()
            val labels = choices.map { it.label }.toTypedArray()
            val dialog = AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setMultiChoiceItems(labels, choices.map { it.selected }.toBooleanArray()) { _, which, checked ->
                    val choice = choices[which]
                    if (checked) selected.add(choice) else selected.remove(choice)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(selected.toTypedArray()))
                }
                .create()
            dialog.setOnDismissListener {
                if (!prompt.isComplete) result.complete(prompt.dismiss())
            }
            dialog.show()
        } else {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                    result.complete(prompt.confirm(choices[which]))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnDismissListener {
                if (!prompt.isComplete) result.complete(prompt.dismiss())
            }
            dialog.show()
        }
        return result
    }

    private fun flattenChoices(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
    ): List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> = buildList {
        choices.forEach { choice ->
            when {
                choice.separator -> Unit
                !choice.items.isNullOrEmpty() -> addAll(flattenChoices(choice.items!!))
                !choice.disabled -> add(choice)
            }
        }
    }

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.DateTimePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val input = EditText(activity).apply {
            setText(prompt.defaultValue)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.date_time_value)
            .setMessage(R.string.date_time_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ColorPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val values = prompt.predefinedValues?.takeIf { it.isNotEmpty() }
            ?: arrayOf("#000000", "#ffffff", "#ff0000", "#00ff00", "#0000ff", "#ffff00", "#00ffff")
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.choose_color)
            .setItems(values) { _, which -> result.complete(prompt.confirm(values[which])) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onFolderUploadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FolderUploadPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.folder_upload_title)
            .setMessage(activity.getString(R.string.folder_upload_message, prompt.directoryName))
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setPositiveButton(R.string.allow) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val host = UriHost.host(prompt.targetUri.orEmpty())
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.popup_title)
            .setMessage(activity.getString(R.string.popup_message, host))
            .setNegativeButton(R.string.deny) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setPositiveButton(R.string.allow) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.SharePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return unavailable(prompt)
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.web_share_title)
            .setMessage(R.string.web_share_message)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.complete(prompt.dismiss())
            }
            .setPositiveButton(R.string.share) { _, _ ->
                try {
                    launchShare(prompt.title, prompt.text, prompt.uri)
                    result.complete(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS))
                } catch (_: Exception) {
                    result.complete(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE))
                }
            }
            .create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onRequestCertificate(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.CertificateRequest,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
        GeckoResult.fromValue(request.confirm(null))

    // Gecko's built-in storage is disabled; the runtime storage delegate seals
    // accepted logins in the app's Android Keystore-backed vault.
    override fun onLoginSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val option = request.options.firstOrNull() ?: return GeckoResult.fromValue(request.dismiss())
        val entry = option.value
        if (!entry.origin.startsWith("https://")) return GeckoResult.fromValue(request.dismiss())
        val site = entry.origin.ifBlank { activity.getString(R.string.this_website) }
        if (activity.isFinishing || activity.isDestroyed) return GeckoResult.fromValue(request.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.save_login)
            .setMessage(activity.getString(R.string.save_login_message, entry.username, site))
            .setNegativeButton(R.string.not_now) { _, _ -> result.complete(request.dismiss()) }
            .setPositiveButton(R.string.save) { _, _ -> result.complete(request.confirm(option)) }
            .create()
        dialog.setOnDismissListener {
            if (!request.isComplete) result.complete(request.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onAddressSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.AddressSaveOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())

    override fun onCreditCardSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.CreditCardSaveOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())

    override fun onLoginSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (request.options.isEmpty() || activity.isFinishing || activity.isDestroyed) {
            return GeckoResult.fromValue(request.dismiss())
        }
        val labels = request.options.map { option ->
            val entry = option.value
            val site = entry.origin.removePrefix("https://").removePrefix("http://")
            "${entry.username} · $site"
        }.toTypedArray()
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.choose_login)
            .setItems(labels) { _, which -> result.complete(request.confirm(request.options[which])) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (!request.isComplete) result.complete(request.dismiss())
        }
        dialog.show()
        return result
    }

    override fun onAddressSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.AddressSelectOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())

    override fun onCreditCardSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.CreditCardSelectOption>,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())

    private object UriHost {
        fun host(uri: String): String = runCatching { android.net.Uri.parse(uri).host }.getOrNull()
            ?.removePrefix("www.") ?: "this website"
    }
}
