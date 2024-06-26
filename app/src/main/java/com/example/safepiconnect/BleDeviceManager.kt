package com.example.safepiconnect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.example.safepiconnect.ProvisionLoading.Companion.DEVICE_NETWORK_CONNECTION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.android.common.core.DataByteArray
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattServices
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger

class BleDeviceManager(
    private val context: Context,
    private val address: String,
    private val onServicesInitialized: (BleDeviceManager) -> Unit
) {
    private lateinit var services: ClientBleGattServices
    private var connection: ClientBleGatt? = null
    val activeOperations = AtomicInteger(0)
    private val lifecycleScope: LifecycleCoroutineScope by lazy {
        (context as? AppCompatActivity)?.lifecycleScope ?: throw IllegalArgumentException("Context must be an AppCompatActivity")
    }

    init {
        connectToDevice()
    }

    private fun connectToDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Bluetooth Connect permission not granted")
                withContext(Dispatchers.Main) {
                }
                return@launch
            }

            // Make a local immutable copy of the mutable property
            val localConnection = ClientBleGatt.connect(context, address, this)
            connection = localConnection

            // Use the local immutable copy for operations
            services = localConnection.discoverServices()
            withContext(Dispatchers.Main) {
                onServicesInitialized(this@BleDeviceManager)
            }
        }
    }

    fun disconnect() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (activeOperations.get() > 0) {
                delay(100)
            }
            connection?.disconnect()
            Log.d(TAG, "Disconnected from the device.")
        }
    }

    fun readChar(serviceID: UUID, readCharUUID: UUID) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!::services.isInitialized) {
                Log.e(TAG, "Services not initialized yet")
                return@launch
            }

            // Ensure permissions are checked on the main thread if needed
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Bluetooth Connect permission not granted")
                return@launch
            }

            activeOperations.incrementAndGet()
            try {
                val service = services.findService(serviceID) ?: throw IllegalStateException("Service not found")
                val readChar = service.findCharacteristic(readCharUUID) ?: throw IllegalStateException("Read characteristic not found")
                val cipherValue = readChar.read()?.value

                cipherValue?.let {
                    val decryptedMessage = AESUtils.decrypt(context.applicationContext, it)
                    Log.d(TAG, "Decrypted Message: ${decryptedMessage.toString(Charsets.UTF_8)}")
                    DEVICE_NETWORK_CONNECTION.postValue(decryptedMessage.toString(Charsets.UTF_8))
                } ?: Log.e(TAG, "Cipher value is null")
            } finally {
                activeOperations.decrementAndGet()
            }
        }
    }

    fun writeChar(message: String, serviceID: UUID, writeCharUUID: UUID, onComplete: (Boolean) -> Unit) {
        if (!::services.isInitialized) {
            Log.e(TAG, "Services not initialized yet")
            return
        }

        lifecycleScope.launch {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                activeOperations.incrementAndGet()
                var success = false
                try {
                    val service = services.findService(serviceID) ?: throw IllegalStateException("Service not found")
                    val writeChar = service.findCharacteristic(writeCharUUID) ?: throw IllegalStateException("Write characteristic not found")
                    val plainText = message.toByteArray(Charsets.UTF_8)

                    // run encryption
                    val encryptedText = AESUtils.encrypt(context.applicationContext, plainText)
                    val dataByteArray = DataByteArray(encryptedText)

                    // write to the char
                    writeChar.write(dataByteArray, BleWriteType.DEFAULT)
                    Log.d(TAG, "Encrypted message written to characteristic")
                    success = true
                } catch (e: GeneralSecurityException) {
                    Log.e(TAG, "Error encrypting message: ${e.localizedMessage}")
                } finally {
                    activeOperations.decrementAndGet()
                    onComplete(success)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleDeviceManager"
        val SERVICE_ID = UUID.fromString("A07498CA-AD5B-474E-940D-16F1FBE7E8CD")
        val READ_CHARACTERISTIC_UUID = UUID.fromString("51FF12BB-3ED8-46E5-B4F9-D64E2FEC021B")
        val WRITE_CHARACTERISTIC_UUID = UUID.fromString("52FF12BB-3ED8-46E5-B4F9-D64E2FEC021B")
    }
}

object AESUtils {
    private fun getEncryptionKey(context: Context): ByteArray {
        val sharedPreferences = context.getSharedPreferences("EncPrefs", Context.MODE_PRIVATE)
        // key string should be: 0123456789ABCDEFFEDCBA98765432100123456789ABCDEFFEDCBA9876543210
        val keyString = sharedPreferences.getString("encryptionKey", null)
            ?: throw IllegalStateException("Encryption key not found")
        return hexStringToByteArray(keyString)
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        }
        return data
    }

    @Throws(GeneralSecurityException::class)
    fun encrypt(context: Context, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(getEncryptionKey(context), "AES")
        val ivSpec = IvParameterSpec(hexStringToByteArray("0123456789ABCDEFFEDCBA9876543210")) // Consider storing IV securely too
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(context: Context, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(getEncryptionKey(context), "AES")
        val ivSpec = IvParameterSpec(hexStringToByteArray("0123456789ABCDEFFEDCBA9876543210"))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }
}

