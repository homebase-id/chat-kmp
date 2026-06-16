package id.homebase.feed.crash

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import id.homebase.feed.R
import java.io.File

/** Runs in a separate `:crash` process so it survives the crashed main process. */
class CrashActivity : AppCompatActivity() {
    companion object { const val EXTRA_REPORT_PATH = "report_path" }

    private var reportPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        reportPath = intent.getStringExtra(EXTRA_REPORT_PATH)

        findViewById<TextView>(R.id.crash_details).text = reportPath
            ?.let { runCatching { File(it).readText() }.getOrNull() }
            ?: getString(R.string.crash_report_unavailable)

        val card = findViewById<View>(R.id.crash_details_card)
        findViewById<Button>(R.id.crash_details_toggle).setOnClickListener {
            card.visibility = if (card.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.crash_restart).setOnClickListener { restart() }
        findViewById<Button>(R.id.crash_share).setOnClickListener { share() }
    }

    private fun restart() {
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }?.let { startActivity(it) }
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    private fun share() {
        val path = reportPath ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
        }
    }
}
