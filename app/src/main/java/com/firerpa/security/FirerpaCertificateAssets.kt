package com.firerpa.security

import java.io.File

data class FirerpaCertificateAssets(
    val directory: File,
    val rootKey: File,
    val rootCert: File,
    val clientPem: File,
    val credential: String,
    val commonName: String,
    val pemBundle: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FirerpaCertificateAssets
        return directory == other.directory &&
            rootKey == other.rootKey &&
            rootCert == other.rootCert &&
            clientPem == other.clientPem &&
            credential == other.credential &&
            commonName == other.commonName &&
            pemBundle.contentEquals(other.pemBundle)
    }

    override fun hashCode(): Int {
        var result = directory.hashCode()
        result = 31 * result + rootKey.hashCode()
        result = 31 * result + rootCert.hashCode()
        result = 31 * result + clientPem.hashCode()
        result = 31 * result + credential.hashCode()
        result = 31 * result + commonName.hashCode()
        result = 31 * result + pemBundle.contentHashCode()
        return result
    }
}
