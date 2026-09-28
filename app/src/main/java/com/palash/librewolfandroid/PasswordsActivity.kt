package com.palash.librewolfandroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PasswordsActivity : AppCompatActivity() {

    private lateinit var store: LoginStore
    private lateinit var adapter: PasswordsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passwords)
        SystemBars.apply(this)
        store = LoginStore(this)

        adapter = PasswordsAdapter(
            emptyList(),
            onOpen = { showLogin(it) },
            onDelete = {
                store.remove(it.site, it.username)
                reload()
            },
        )
        findViewById<RecyclerView>(R.id.passwords_list).apply {
            layoutManager = LinearLayoutManager(this@PasswordsActivity)
            adapter = this@PasswordsActivity.adapter
        }
        findViewById<View>(R.id.passwords_back).setOnClickListener { finish() }
        findViewById<View>(R.id.passwords_add).setOnClickListener {
            addDialog(intent.getStringExtra(EXTRA_SITE).orEmpty())
        }
        intent.getStringExtra(EXTRA_SITE)?.let { site ->
            if (site.isNotEmpty()) addDialog(site)
            intent.removeExtra(EXTRA_SITE)
        }
        reload()
    }

    private fun reload() = adapter.update(store.all())

    private fun addDialog(prefillSite: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val site = EditText(this).apply { hint = getString(R.string.login_site); setText(prefillSite) }
        val user = EditText(this).apply { hint = getString(R.string.login_user) }
        val pass = EditText(this).apply {
            hint = getString(R.string.login_pass)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(site)
        box.addView(user)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_login))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                if (site.text.isNotEmpty() && pass.text.isNotEmpty()) {
                    store.add(site.text.toString().trim(), user.text.toString(), pass.text.toString())
                    reload()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLogin(login: Login) {
        AlertDialog.Builder(this)
            .setTitle(login.site)
            .setMessage(
                getString(R.string.login_user) + ": " + login.username +
                    "\n" + getString(R.string.password_hidden),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.copy_password) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(login.username, login.password))
                android.widget.Toast.makeText(this, R.string.copied_to_clipboard, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.delete)) { _, _ ->
                store.remove(login.site, login.username)
                reload()
            }
            .show()
    }

    class PasswordsAdapter(
        private var items: List<Login>,
        private val onOpen: (Login) -> Unit,
        private val onDelete: (Login) -> Unit,
    ) : RecyclerView.Adapter<PasswordsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val site: TextView = v.findViewById(R.id.pw_site)
            val user: TextView = v.findViewById(R.id.pw_user)
            val delete: ImageButton = v.findViewById(R.id.pw_delete)
        }

        fun update(items: List<Login>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_password, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.site.text = e.site
            h.user.text = e.username.ifEmpty { "—" }
            h.itemView.setOnClickListener { onOpen(e) }
            h.delete.setOnClickListener { onDelete(e) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val EXTRA_SITE = "site"
    }
}
