package io.github.yuriyurin.fit3companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal object AboutLinks {
    const val AUTHOR = "https://4pda.to/forum/index.php?showuser=4729981"
    const val SUPPORT = "https://www.donationalerts.com/r/yuriyurinnn"
}

/** External links never replace the app's current page. */
internal fun openAboutLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.about_link_unavailable), Toast.LENGTH_SHORT).show()
    }
}
