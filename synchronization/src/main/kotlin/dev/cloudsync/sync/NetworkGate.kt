package dev.cloudsync.sync

import dev.cloudsync.core.model.NetworkMode
import dev.cloudsync.core.model.NetworkSnapshot
import dev.cloudsync.core.model.SyncFolder
import dev.cloudsync.core.model.TransferDirection

object NetworkGate {
    fun waitReason(folder: SyncFolder, network: NetworkSnapshot, direction: TransferDirection): String? {
        if (!network.validated) return "Waiting for a network connection."
        if (folder.requireCharging && !network.charging) return "Waiting until the device is charging."
        val battery = network.batteryPercent
        if (battery != null && battery < folder.minBatteryPercent) {
            return "Waiting until the battery is at least ${folder.minBatteryPercent}%."
        }
        val unmeteredWifi = network.wifi && !network.metered
        val mobile = network.cellular || (network.metered && !network.wifi)
        val modeAllowed = when (folder.networkMode) {
            NetworkMode.WIFI_ONLY -> unmeteredWifi
            NetworkMode.MOBILE_ONLY -> mobile && !unmeteredWifi
            NetworkMode.WIFI_AND_MOBILE -> unmeteredWifi || mobile || network.wifi
        }
        if (!modeAllowed) return "Waiting for an allowed network."
        val onWifi = network.wifi && !mobile
        val permitted = when (direction) {
            TransferDirection.UPLOAD -> if (onWifi || unmeteredWifi) folder.allowUploadOnWifi else folder.allowUploadOnMobile
            TransferDirection.DOWNLOAD -> if (onWifi || unmeteredWifi) folder.allowDownloadOnWifi else folder.allowDownloadOnMobile
        }
        if (!permitted) return "This ${direction.name.lowercase()} is not allowed on the current network."
        return null
    }
}
