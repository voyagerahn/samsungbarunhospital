package io.voyager.samsungbarunhospital.network.ble

import java.security.SecureRandom

private val secureRandom = SecureRandom()

/** Cryptographically random bytes, used for peerIds, messageIds and fragmentIds. */
fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
