package com.firerpa.security

import android.content.Context
import android.os.Environment
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMKeyPair

object FirerpaCertificateManager {
    private const val CERT_DIR = "certs"
    private const val ROOT_KEY_FILE = "root.key"
    private const val ROOT_CERT_FILE = "root.crt"
    private const val CLIENT_PEM_FILE = "client.pem"
    private const val COMMON_NAME_FILE = "client.cn"
    private const val CERT_SCHEMA_FILE = "cert.schema"
    private const val CERT_SCHEMA_VERSION = "2"
    private const val VALIDITY_SECONDS = 315_360_000L
    private const val ORGANIZATION = "FIRERPA"
    private const val PROVIDER = BouncyCastleProvider.PROVIDER_NAME
    const val ROOT_CERT_EXPORT_FILE_NAME = "firerpa-root-ca.crt"

    init {
        synchronized(FirerpaCertificateManager::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun certificateDirectory(context: Context): File {
        return File(context.filesDir, CERT_DIR)
    }

    fun readStoredCredential(context: Context): String {
        return defaultSslWebCredential(context)
    }

    fun readClientPemText(context: Context): String {
        ensureGenerated(context)
        return File(certificateDirectory(context), CLIENT_PEM_FILE).readText()
    }

    fun defaultSslWebCredential(context: Context): String {
        return deriveCredentialFromClientPem(readClientPemText(context))
    }

    fun deriveCredentialFromClientPem(pemContent: String): String {
        if (pemContent.isBlank()) {
            return ""
        }
        return deriveCredential(readClientPrivateKeyFromPem(pemContent))
    }

    fun readClientPemBase64(context: Context): String {
        val file = File(certificateDirectory(context), CLIENT_PEM_FILE)
        check(file.isFile) { "client.pem is missing" }
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    fun exportRootCertificateToDownloads(context: Context): File {
        val assets = ensureGenerated(context)
        check(assets.rootCert.isFile) { "Root certificate is missing" }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(downloadsDir.exists() || downloadsDir.mkdirs()) {
            "Downloads directory is unavailable"
        }
        val destination = File(downloadsDir, ROOT_CERT_EXPORT_FILE_NAME)
        assets.rootCert.copyTo(destination, overwrite = true)
        return destination
    }

    fun ensureGenerated(context: Context): FirerpaCertificateAssets {
        val directory = certificateDirectory(context).apply { mkdirs() }
        removeLegacyCredentialFile(directory)
        invalidateCertsIfSchemaOutdated(directory)
        val rootKey = File(directory, ROOT_KEY_FILE)
        val rootCert = File(directory, ROOT_CERT_FILE)
        val clientPem = File(directory, CLIENT_PEM_FILE)
        val commonNameFile = File(directory, COMMON_NAME_FILE)
        val commonName = DeviceCnSanitizer.fromDevice(context)

        if (!rootKey.isFile || !rootCert.isFile) {
            createRootKeyPair(rootKey, rootCert)
        }

        val needsClientRegeneration = !clientPem.isFile ||
            !commonNameFile.isFile ||
            commonNameFile.readText() != commonName

        if (needsClientRegeneration) {
            val generated = generateClientPem(
                commonName = commonName,
                rootKeyFile = rootKey,
                rootCertFile = rootCert,
            )
            clientPem.writeBytes(generated.pemBundle)
            commonNameFile.writeText(commonName)
            val pemText = String(generated.pemBundle, Charsets.US_ASCII)
            markCertSchemaCurrent(directory)
            return FirerpaCertificateAssets(
                directory = directory,
                rootKey = rootKey,
                rootCert = rootCert,
                clientPem = clientPem,
                credential = deriveCredentialFromClientPem(pemText),
                commonName = commonName,
                pemBundle = generated.pemBundle,
            )
        }

        val pemBundle = clientPem.readBytes()
        markCertSchemaCurrent(directory)
        return FirerpaCertificateAssets(
            directory = directory,
            rootKey = rootKey,
            rootCert = rootCert,
            clientPem = clientPem,
            credential = deriveCredentialFromClientPem(clientPem.readText()),
            commonName = commonName,
            pemBundle = pemBundle,
        )
    }

    internal fun createRootKeyPair(keyFile: File, certFile: File) {
        val keyPair = generateRsaKeyPair()
        val subject = X500Name("O=$ORGANIZATION")
        val certificate = buildCertificate(
            issuer = subject,
            subject = subject,
            publicKey = keyPair.public,
            signingKey = keyPair.private,
            isCa = true,
        )
        writePrivateKeyPem(keyFile, keyPair.private)
        writeCertificatePem(certFile, certificate)
    }

    internal fun generateClientPem(
        commonName: String,
        rootKeyFile: File,
        rootCertFile: File,
    ): GeneratedClientPem {
        val rootKey = readPrivateKey(rootKeyFile)
        val rootCertificate = readCertificate(rootCertFile)
        val rootSubject = JcaX509CertificateHolder(rootCertificate).subject
        val clientKeyPair = generateRsaKeyPair()
        val clientSubject = X500Name("CN=$commonName")
        val clientCertificate = buildCertificate(
            issuer = rootSubject,
            subject = clientSubject,
            publicKey = clientKeyPair.public,
            signingKey = rootKey,
            isCa = false,
        )

        val clientKeyPem = encodePem("PRIVATE KEY", clientKeyPair.private.encoded)
        val clientCertPem = encodePem("CERTIFICATE", clientCertificate.encoded)
        val rootCertPem = encodePem("CERTIFICATE", rootCertificate.encoded)
        val pemBundle = sequenceOf(clientKeyPem, clientCertPem, rootCertPem)
            .joinToString("\n")
            .toByteArray(Charsets.US_ASCII)

        return GeneratedClientPem(
            pemBundle = pemBundle,
        )
    }

    internal fun deriveCredential(privateKey: PrivateKey): String {
        val rsaKey = privateKey as RSAPrivateKey
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bigIntegerToLittleEndianBytes(rsaKey.privateExponent))
        val hex = digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
        return buildString(hex.length / 3 + hex.length % 3) {
            for (index in hex.indices step 3) {
                append(hex[index])
            }
        }
    }

    internal fun bigIntegerToLittleEndianBytes(value: BigInteger): ByteArray {
        val byteLength = (value.bitLength() + 7) / 8
        val bytes = ByteArray(byteLength)
        var current = value
        for (index in 0 until byteLength) {
            bytes[index] = (current and BigInteger.valueOf(0xFF)).toByte()
            current = current.shiftRight(8)
        }
        return bytes
    }

    internal data class GeneratedClientPem(
        val pemBundle: ByteArray,
    )

    private fun removeLegacyCredentialFile(directory: File) {
        File(directory, "credential.txt").takeIf { it.isFile }?.delete()
    }

    private fun readClientPrivateKeyFromPem(pemContent: String): PrivateKey {
        StringReader(pemContent).use { reader ->
            PEMParser(reader).use { parser ->
                val converter = JcaPEMKeyConverter().setProvider(PROVIDER)
                while (true) {
                    when (val keyObject = parser.readObject() ?: break) {
                        is PEMKeyPair -> return converter.getPrivateKey(keyObject.privateKeyInfo)
                        is PrivateKeyInfo -> return converter.getPrivateKey(keyObject)
                    }
                }
                error("Missing private key in client.pem")
            }
        }
    }

    private fun invalidateCertsIfSchemaOutdated(directory: File) {
        val schemaFile = File(directory, CERT_SCHEMA_FILE)
        if (schemaFile.isFile && schemaFile.readText().trim() == CERT_SCHEMA_VERSION) {
            return
        }
        listOf(
            ROOT_KEY_FILE,
            ROOT_CERT_FILE,
            CLIENT_PEM_FILE,
            COMMON_NAME_FILE,
            "credential.txt",
        ).forEach { name ->
            File(directory, name).takeIf { it.isFile }?.delete()
        }
    }

    private fun markCertSchemaCurrent(directory: File) {
        File(directory, CERT_SCHEMA_FILE).writeText(CERT_SCHEMA_VERSION)
    }

    private fun generateRsaKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048, SecureRandom()) }
            .generateKeyPair()
    }

    private fun buildCertificate(
        issuer: X500Name,
        subject: X500Name,
        publicKey: java.security.PublicKey,
        signingKey: PrivateKey,
        isCa: Boolean,
    ): X509Certificate {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + VALIDITY_SECONDS * 1000)
        val serial = BigInteger(128, SecureRandom())
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            subject,
            publicKey,
        )
        if (isCa) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
        } else {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(PROVIDER)
            .build(signingKey)
        return JcaX509CertificateConverter()
            .setProvider(PROVIDER)
            .getCertificate(builder.build(signer))
    }

    private fun readPrivateKey(file: File): PrivateKey {
        FileReader(file).use { reader ->
            PEMParser(reader).use { parser ->
                val keyObject = parser.readObject()
                    ?: error("Missing private key in ${file.absolutePath}")
                val converter = JcaPEMKeyConverter().setProvider(PROVIDER)
                return when (keyObject) {
                    is PEMKeyPair -> converter.getPrivateKey(keyObject.privateKeyInfo)
                    is PrivateKeyInfo -> converter.getPrivateKey(keyObject)
                    else -> error("Unsupported private key format in ${file.absolutePath}")
                }
            }
        }
    }

    private fun readCertificate(file: File): X509Certificate {
        FileReader(file).use { reader ->
            PEMParser(reader).use { parser ->
                val certificateObject = parser.readObject()
                    ?: error("Missing certificate in ${file.absolutePath}")
                return JcaX509CertificateConverter()
                    .setProvider(PROVIDER)
                    .getCertificate(certificateObject as X509CertificateHolder)
            }
        }
    }

    private fun writePrivateKeyPem(file: File, privateKey: PrivateKey) {
        file.bufferedWriter().use { writer ->
            JcaPEMWriter(writer).use { pemWriter ->
                pemWriter.writeObject(privateKey)
            }
        }
    }

    private fun writeCertificatePem(file: File, certificate: X509Certificate) {
        file.bufferedWriter().use { writer ->
            JcaPEMWriter(writer).use { pemWriter ->
                pemWriter.writeObject(certificate)
            }
        }
    }

    private fun encodePem(type: String, encoded: ByteArray): String {
        val output = ByteArrayOutputStream()
        OutputStreamWriter(output, Charsets.US_ASCII).use { writer ->
            PemWriter(writer).use { pemWriter ->
                pemWriter.writeObject(PemObject(type, encoded))
            }
        }
        return String(output.toByteArray(), Charsets.US_ASCII).trim()
    }
}
