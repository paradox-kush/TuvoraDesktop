package com.nuvio.app.core.startup

import android.content.Context
import androidx.activity.ComponentActivity

/**
 * Neutral seam for Android process/activity startup work that fork features need run at boot —
 * DB-driver init, the IPTV refresh worker, the M3U file-picker's activity-result launcher. The
 * fork references live in the exempt AndroidFeatureWiring; MainActivity (a platform composition
 * root, but one the firewall still holds to zero crossings) drives this registry instead of naming
 * the fork drivers directly.
 */
fun interface AndroidStartupTask {
    fun run(context: Context)
}

fun interface AndroidActivityBinder {
    fun bind(activity: ComponentActivity)
}

object AndroidStartup {
    private val tasks = mutableListOf<AndroidStartupTask>()
    private val binders = mutableListOf<AndroidActivityBinder>()

    fun registerTask(task: AndroidStartupTask) { tasks += task }
    fun registerBinder(binder: AndroidActivityBinder) { binders += binder }

    /** Context-scoped startup (idempotent per driver); safe to re-run on activity recreate. */
    fun runStartup(context: Context) { tasks.forEach { it.run(context) } }

    /** Activity-scoped binding (registerForActivityResult launchers) — must run in onCreate. */
    fun bindActivity(activity: ComponentActivity) { binders.forEach { it.bind(activity) } }
}
