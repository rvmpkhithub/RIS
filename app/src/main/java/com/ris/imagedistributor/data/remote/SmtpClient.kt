package com.ris.imagedistributor.data.remote

import com.ris.imagedistributor.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Gmail SMTP over STARTTLS, via the Android-compatible JavaMail port (`com.sun.mail:android-mail`
 * + `android-activation`) — the plain desktop `jakarta.mail`/`javax.mail` artifacts depend on
 * `javax.activation`/JNDI machinery not present on the Android runtime. [ARCHITECTURE-SPINE.md#Stack]
 *
 * No unit test for this class directly — a thin wrapper around real blocking network I/O, same
 * convention as this codebase's untested `ComplianceApi`/`RegistrationApi` Retrofit interfaces
 * (the repository layer above them is what's tested).
 */
class SmtpClient {

    suspend fun sendImage(to: String, imageFile: File) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", AppConfig.SMTP_HOST)
            put("mail.smtp.port", AppConfig.SMTP_PORT.toString())
            // JavaMail has no sane default here — an unset value can block indefinitely.
            put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MILLIS)
            put("mail.smtp.timeout", READ_TIMEOUT_MILLIS)
            put("mail.smtp.writetimeout", WRITE_TIMEOUT_MILLIS)
        }
        val session = Session.getInstance(
            props,
            object : javax.mail.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(AppConfig.SMTP_USERNAME, AppConfig.SMTP_APP_PASSWORD)
            },
        )

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(AppConfig.SMTP_USERNAME))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            subject = "Your daily images"
        }

        val textPart = MimeBodyPart().apply { setText("Please find today's images attached.") }
        val imagePart = MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(imageFile))
            fileName = imageFile.name
        }
        message.setContent(MimeMultipart().apply { addBodyPart(textPart); addBodyPart(imagePart) })

        Transport.send(message)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = "15000"
        const val READ_TIMEOUT_MILLIS = "15000"
        const val WRITE_TIMEOUT_MILLIS = "15000"
    }
}
