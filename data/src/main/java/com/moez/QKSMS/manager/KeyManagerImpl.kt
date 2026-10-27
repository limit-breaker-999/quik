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
package dev.octoshrimpy.quik.manager

import dev.octoshrimpy.quik.model.Message
import io.realm.Realm
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManagerImpl(
    private val realmProvider: () -> Realm
) : KeyManager {

    @Inject
    constructor() : this({ Realm.getDefaultInstance() })

    private val lock = Any()
    private var initialized = false
    private var maxValue: Long = 0

    /**
     * Should be called when a new sync is being started
     */
    override fun reset() {
        synchronized(lock) {
            initialized = false
        }
    }

    /**
     * Returns a valid ID that can be used to store a new message
     */
    override fun newId(): Long {
        synchronized(lock) {
            if (!initialized) {
                maxValue = realmProvider().use { realm ->
                    realm.where(Message::class.java).max("id")?.toLong() ?: 0L
                }
                initialized = true
            }

            maxValue++
            return maxValue
        }
    }

}