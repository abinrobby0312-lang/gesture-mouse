package com.gesturemouse

import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gesturemouse.databinding.DialogBugReportBinding
import java.util.concurrent.Executors

/**
 * The "Report a bug" dialog.
 *
 * The person sees what will be sent before anything is, and nothing goes until
 * they tap Send. If sending fails the dialog stays put, note and all, so a bad
 * signal doesn't cost them what they typed.
 */
object BugReportDialog {

    private val executor = Executors.newSingleThreadExecutor()

    /** [status] is the connection line shown in the app, to be scrubbed and sent with the log. */
    fun show(activity: AppCompatActivity, status: String) {
        val b = DialogBugReportBinding.inflate(LayoutInflater.from(activity))
        b.bugPreview.movementMethod = ScrollingMovementMethod()
        // a scrolling preview inside a scrolling dialog: let it keep its own touches
        b.bugPreview.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        b.bugPreviewToggle.setOnClickListener {
            val show = b.bugPreview.visibility != View.VISIBLE
            b.bugPreview.visibility = if (show) View.VISIBLE else View.GONE
            b.bugPreviewToggle.text = if (show) "Hide what will be sent" else "See exactly what will be sent"
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Report a bug 🐞")
            .setView(b.root)
            // wired below: the default would close the dialog before we know it worked
            .setPositiveButton("Send", null)
            .setNegativeButton("Cancel", null)
            .create()

        var snapshot: BugReport.Snapshot? = null

        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.isEnabled = false

            // reading the log takes a moment; Send waits for it
            executor.execute {
                val s = BugReport.snapshot(activity.applicationContext, status)
                activity.runOnUiThread {
                    if (activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    snapshot = s
                    b.bugPreview.text = s.preview()
                    send.isEnabled = true
                }
            }

            send.setOnClickListener {
                val s = snapshot ?: return@setOnClickListener
                val email = b.bugEmail.text?.toString()?.trim().orEmpty()
                if (email.isNotEmpty() && !BugReport.isValidEmail(email)) {
                    b.bugEmailLayout.error = "That doesn't look like an email address"
                    return@setOnClickListener
                }
                b.bugEmailLayout.error = null
                send.isEnabled = false
                b.bugStatus.text = "Sending…"

                val note = b.bugDescription.text?.toString()
                executor.execute {
                    val ok = try { BugReport.send(s, note, email) } catch (_: Exception) { false }
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        if (ok) {
                            dialog.dismiss()
                            Toast.makeText(activity, "Thanks — the bug is on our list 🐞", Toast.LENGTH_LONG).show()
                        } else {
                            b.bugStatus.text = "Couldn't send. Check your connection and try again."
                            send.isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
