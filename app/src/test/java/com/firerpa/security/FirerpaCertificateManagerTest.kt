package com.firerpa.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.openssl.PEMParser
import java.io.StringReader
import java.math.BigInteger
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory

class FirerpaCertificateManagerTest {
    @Test
    fun bigIntegerToLittleEndianBytesUsesMinimalLength() {
        val bytes = FirerpaCertificateManager.bigIntegerToLittleEndianBytes(BigInteger.valueOf(255L))
        assertEquals(1, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
    }

    @Test
    fun generateRootAndClientPemBundle() {
        val dir = createTempDirectory().toFile()
        val rootKey = java.io.File(dir, "root.key")
        val rootCert = java.io.File(dir, "root.crt")

        FirerpaCertificateManager.createRootKeyPair(rootKey, rootCert)
        val generated = FirerpaCertificateManager.generateClientPem(
            commonName = "Pixel_8_Pro",
            rootKeyFile = rootKey,
            rootCertFile = rootCert,
        )
        val pemText = generated.pemBundle.decodeToString()
        val credential = FirerpaCertificateManager.deriveCredentialFromClientPem(pemText)
        assertTrue(credential.isNotEmpty())
        assertEquals(3, pemText.split("-----BEGIN").size - 1)
        assertTrue(pemText.contains("BEGIN PRIVATE KEY"))
        assertTrue(pemText.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun clientCertificateIsSignedByRootCaWithCaExtensions() {
        val dir = createTempDirectory().toFile()
        val rootKey = java.io.File(dir, "root.key")
        val rootCertFile = java.io.File(dir, "root.crt")

        FirerpaCertificateManager.createRootKeyPair(rootKey, rootCertFile)
        val generated = FirerpaCertificateManager.generateClientPem(
            commonName = "Pixel_8_Pro",
            rootKeyFile = rootKey,
            rootCertFile = rootCertFile,
        )
        val certificates = parseCertificates(generated.pemBundle.decodeToString())
        assertEquals(2, certificates.size)

        val rootCertificate = certificates[1]
        val clientCertificate = certificates[0]
        val rootHolder = JcaX509CertificateHolder(rootCertificate)

        assertTrue(BasicConstraints.getInstance(rootHolder.getExtension(Extension.basicConstraints).parsedValue).isCA)
        assertEquals(rootCertificate.subjectX500Principal, clientCertificate.issuerX500Principal)
        clientCertificate.verify(rootCertificate.publicKey)
    }

    private fun parseCertificates(pemText: String): List<X509Certificate> {
        val parser = PEMParser(StringReader(pemText))
        val converter = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
        val certificates = mutableListOf<X509Certificate>()
        while (true) {
            val item = parser.readObject() ?: break
            if (item is org.bouncycastle.cert.X509CertificateHolder) {
                certificates.add(converter.getCertificate(item))
            }
        }
        parser.close()
        return certificates
    }
}
