package sk.alexia.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** výsledok inštalácie aktualizácie: ak systém potrebuje potvrdenie, otvorí jeho dialóg */
class AktualizaciaPrijimac : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val potvrd = (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                runCatching { c.startActivity(potvrd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // appka sa reštartuje do novej verzie
            else -> {
                val sprava = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                runCatching { Platforma.oznam("⚠ " + T("akt_nepodarilo") + " " + sprava) }
            }
        }
    }
}
