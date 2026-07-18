package com.ris.imagedistributor.di

import android.content.Context
import androidx.room.Room
import com.ris.imagedistributor.config.AppConfig
import com.ris.imagedistributor.data.local.AppDatabase
import com.ris.imagedistributor.data.local.ImageFileStore
import com.ris.imagedistributor.data.remote.ComplianceApi
import com.ris.imagedistributor.data.remote.RegistrationApi
import com.ris.imagedistributor.data.remote.SmtpClient
import com.ris.imagedistributor.data.remote.SubmissionNotificationApi
import com.ris.imagedistributor.data.remote.WhatsAppApi
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.data.repository.ComplianceRepositoryImpl
import com.ris.imagedistributor.data.repository.DeliveryRepository
import com.ris.imagedistributor.data.repository.DeliveryRepositoryImpl
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.ImageRepositoryImpl
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
import com.ris.imagedistributor.data.repository.MasterScheduleRepositoryImpl
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.ReceiverRepositoryImpl
import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.data.repository.RetentionRepositoryImpl
import com.ris.imagedistributor.data.repository.SubmissionNotificationRepository
import com.ris.imagedistributor.data.repository.SubmissionNotificationRepositoryImpl
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.data.repository.TransmissionRepositoryImpl
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.ImageSelectionEngine
import com.ris.imagedistributor.domain.RetentionPolicy
import com.ris.imagedistributor.worker.SendDispatcher
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual DI — no framework. [AD stack decision: manual AppContainer, not Hilt]
 *
 * One shared OkHttpClient/Json instance backs every Retrofit service this app builds — reused
 * by later stories' API clients (WhatsApp Business API, etc.) via [buildRetrofit], never
 * duplicated. [AD-2, implementation-readiness-report-2026-07-09.md#Minor Concern 1]
 */
class AppContainer(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val sharedOkHttpClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    /** [sharedOkHttpClient] plus a Bearer-token Authorization header — WhatsApp Cloud API only; Compliance/Registration have no auth. */
    private val whatsAppOkHttpClient: OkHttpClient by lazy {
        sharedOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${AppConfig.WHATSAPP_API_TOKEN}")
                        .build()
                )
            }
            .build()
    }

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient = sharedOkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
            )
            .build()
    }

    private val imageFileStore: ImageFileStore by lazy { ImageFileStore(context.applicationContext) }

    val imageRepository: ImageRepository by lazy {
        ImageRepositoryImpl(dao = database.imageDao(), fileStore = imageFileStore)
    }

    val receiverRepository: ReceiverRepository by lazy {
        ReceiverRepositoryImpl(database = database)
    }

    private val registrationApi: RegistrationApi by lazy {
        buildRetrofit(AppConfig.REGISTRATION_API_URL).create(RegistrationApi::class.java)
    }

    private val complianceApi: ComplianceApi by lazy {
        buildRetrofit(AppConfig.COMPLIANCE_API_URL).create(ComplianceApi::class.java)
    }

    val complianceRepository: ComplianceRepository by lazy {
        ComplianceRepositoryImpl(
            dao = database.complianceStateDao(),
            registrationApi = registrationApi,
            complianceApi = complianceApi,
        )
    }

    val complianceGate: ComplianceGate by lazy {
        ComplianceGate(complianceRepository)
    }

    val transmissionRepository: TransmissionRepository by lazy {
        TransmissionRepositoryImpl(dao = database.transmissionDao())
    }

    val retentionRepository: RetentionRepository by lazy {
        RetentionRepositoryImpl(dao = database.retentionSettingDao())
    }

    val masterScheduleRepository: MasterScheduleRepository by lazy {
        MasterScheduleRepositoryImpl(database = database)
    }

    val imageSelectionEngine: ImageSelectionEngine by lazy {
        ImageSelectionEngine(imageRepository)
    }

    private val whatsAppApi: WhatsAppApi by lazy {
        buildRetrofit(AppConfig.WHATSAPP_API_URL, whatsAppOkHttpClient).create(WhatsAppApi::class.java)
    }

    private val smtpClient: SmtpClient by lazy { SmtpClient() }

    val deliveryRepository: DeliveryRepository by lazy {
        DeliveryRepositoryImpl(whatsAppApi = whatsAppApi, smtpClient = smtpClient, imageFileStore = imageFileStore)
    }

    private val submissionNotificationApi: SubmissionNotificationApi by lazy {
        buildRetrofit(AppConfig.SUBMISSION_NOTIFICATION_API_URL).create(SubmissionNotificationApi::class.java)
    }

    val submissionNotificationRepository: SubmissionNotificationRepository by lazy {
        SubmissionNotificationRepositoryImpl(api = submissionNotificationApi)
    }

    val sendDispatcher: SendDispatcher by lazy {
        SendDispatcher(
            receiverRepository = receiverRepository,
            imageRepository = imageRepository,
            imageSelectionEngine = imageSelectionEngine,
            transmissionRepository = transmissionRepository,
            deliveryRepository = deliveryRepository,
            complianceRepository = complianceRepository,
            complianceGate = complianceGate,
            masterScheduleRepository = masterScheduleRepository,
            submissionNotificationRepository = submissionNotificationRepository,
        )
    }

    val retentionPolicy: RetentionPolicy by lazy {
        RetentionPolicy(retentionRepository, transmissionRepository)
    }
}
