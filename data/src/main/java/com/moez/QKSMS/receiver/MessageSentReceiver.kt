/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.klinker.android.send_message.MmsSentReceiver.EXTRA_FILE_PATH
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.interactor.MarkFailed
import dev.octoshrimpy.quik.interactor.MarkSent
import dev.octoshrimpy.quik.repository.MessageRepository
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class MessageSentReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_QUIK_MESSAGE_ID = "messageId"
        const val EXTRA_IS_NOTIFY = "isNotify"
    }

    @Inject lateinit var markSent: MarkSent
    @Inject lateinit var markFailed: MarkFailed
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var automationRepo: dev.octoshrimpy.quik.repository.AutomationRuleRepository

    override fun onReceive(context: Context?, intent: Intent) {
        AndroidInjection.inject(this, context)

        Timber.v("received")

        // if have EXTRA_FILE_PATH then need to delete mms cache file
        intent.extras?.getString(EXTRA_FILE_PATH)?.let { filePath ->
            Timber.v("delete mms temp file $filePath")
            File(filePath).delete()
        }

        if (intent.extras?.getInt(EXTRA_IS_NOTIFY, -1) != -1) {
            Timber.v("notify message sent resultcode $resultCode")
            return
        }

        intent.extras?.getLong(EXTRA_QUIK_MESSAGE_ID)?.takeIf { it > 0 }
            ?.let { messageId ->
                Timber.v("resultcode: $resultCode")
                val pendingResult = goAsync()

                try {
                    automationRepo.log(
                        0L,
                        "radio",
                        messageId,
                        if (pendingResult.resultCode == Activity.RESULT_OK) dev.octoshrimpy.quik.model.AutomationLog.TYPE_INFO
                        else dev.octoshrimpy.quik.model.AutomationLog.TYPE_ERROR,
                        "RADIO_ACK",
                        "Radio ack resultCode=${pendingResult.resultCode} for message $messageId",
                        dev.octoshrimpy.quik.model.AutomationDiag.json(
                            "stage" to "radio_ack",
                            "outcome" to if (pendingResult.resultCode == Activity.RESULT_OK) "sent" else "failed",
                            "outboundMessageId" to messageId,
                            "resultCode" to pendingResult.resultCode
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to log radio ack")
                }
                when (pendingResult.resultCode) {
                    Activity.RESULT_OK ->
                        markSent.execute(messageId) { pendingResult.finish() }

                    else -> markFailed.execute(
                        MarkFailed.Params(messageId, pendingResult.resultCode)
                    ) {
                        pendingResult.finish()
                    }
                }
            } ?: let { Timber.e("couldn't get message id") }
    }

}