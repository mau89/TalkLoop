@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.mau89.talkloop

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/** Device-only Keychain item; the service token never enters NSUserDefaults. */
internal object ServiceTokenStorage {
    // Security constants are CFString pointers; bridge them to NSString keys/values
    // before constructing an NSDictionary. Boxing CPointer as a map key is invalid.
    private fun attribute(value: CFStringRef?): Any = CFBridgingRelease(CFRetain(value))!!
    private fun dictionary(values: Map<Any, Any>): CFDictionaryRef = CFBridgingRetain(values)!!.reinterpret()
    private fun query() = mapOf(
        attribute(kSecClass) to attribute(kSecClassGenericPassword),
        attribute(kSecAttrService) to "com.mau89.talkloop.private-ai",
        attribute(kSecAttrAccount) to "service-token",
    )

    fun load(): String = memScoped {
        val result = alloc<CFTypeRefVar>()
        val attributes = dictionary(query() + mapOf(attribute(kSecReturnData) to true,
            attribute(kSecMatchLimit) to attribute(kSecMatchLimitOne)))
        try {
            if (SecItemCopyMatching(attributes, result.ptr) != errSecSuccess) return ""
            val data = CFBridgingRelease(result.value) as? NSData ?: return ""
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().orEmpty()
        } finally { CFRelease(attributes) }
    }

    fun save(value: String) {
        val base = dictionary(query())
        try {
            if (value.isEmpty()) {
                val status = SecItemDelete(base)
                check(status == errSecSuccess || status == errSecItemNotFound) { "Не удалось удалить ключ сервиса" }
                return
            }
            val bytes = value.encodeToByteArray()
            val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            val update = dictionary(mapOf(attribute(kSecValueData) to data))
            val status = try { SecItemUpdate(base, update) }
                finally { CFRelease(update) }
            if (status == errSecItemNotFound) {
                val item = dictionary(query() + mapOf(attribute(kSecValueData) to data,
                    attribute(kSecAttrAccessible) to attribute(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)))
                try { check(SecItemAdd(item, null) == errSecSuccess) { "Не удалось сохранить ключ сервиса" } }
                finally { CFRelease(item) }
            } else check(status == errSecSuccess) { "Не удалось обновить ключ сервиса" }
        } finally { CFRelease(base) }
    }
}
