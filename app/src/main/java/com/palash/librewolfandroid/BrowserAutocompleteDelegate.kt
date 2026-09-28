package com.palash.librewolfandroid

import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

/** Connects Gecko form autofill to the encrypted on-device vault. */
class BrowserAutocompleteDelegate(private val loginStore: LoginStore) : Autocomplete.StorageDelegate {
    override fun onLoginFetch(origin: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        if (!origin.startsWith("https://")) return GeckoResult.fromValue(emptyArray())
        val host = hostOf(origin)
        return GeckoResult.fromValue(
            loginStore.all()
                .filter { it.site.startsWith("https://") && hostOf(it.site) == host }
                .map(::toEntry)
                .toTypedArray(),
        )
    }

    // The origin-specific callback below is the only path that receives the
    // site's origin. Never hand the whole vault to an origin-less engine query.
    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> =
        GeckoResult.fromValue(emptyArray())

    override fun onLoginSave(entry: Autocomplete.LoginEntry) {
        if (entry.origin.startsWith("https://") && entry.username.isNotBlank()) {
            loginStore.add(entry.origin, entry.username, entry.password)
        }
    }

    override fun onLoginUsed(entry: Autocomplete.LoginEntry, flags: Int) = Unit

    private fun toEntry(login: Login): Autocomplete.LoginEntry =
        Autocomplete.LoginEntry.Builder()
            .guid(login.site + "|" + login.username)
            .origin(login.site)
            .formActionOrigin(login.site)
            .username(login.username)
            .password(login.password)
            .build()

    private fun hostOf(value: String): String = runCatching {
        android.net.Uri.parse(value).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")
}
