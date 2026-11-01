package com.casa.tv

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?, val intent: Intent)

/** Launchable apps on this TV, TV (leanback) entries first. */
object Apps {
    /** The home screen's big tiles: YouTube (Fire TV or Google TV) and Xuper. */
    val favorites = listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.xuper.netxxus")

    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER)) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val ai = r.activityInfo
                if (ai.packageName == ctx.packageName || ai.packageName in found) continue
                val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (_: Throwable) { null }
                found[ai.packageName] = AppEntry(ai.packageName, r.loadLabel(pm).toString(), banner, r.loadIcon(pm), intent)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun favorites(ctx: Context): List<AppEntry> {
        val byPkg = all(ctx).associateBy { it.pkg }
        return favorites.mapNotNull { byPkg[it] }
    }

    fun open(ctx: Context, app: AppEntry) {
        try { ctx.startActivity(app.intent) } catch (_: Throwable) {}
    }
}
