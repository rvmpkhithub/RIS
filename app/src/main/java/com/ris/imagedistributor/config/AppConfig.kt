package com.ris.imagedistributor.config

/**
 * Single source of truth for hardcoded external endpoints. [AD-3]
 * No other file in this project may declare these URLs.
 *
 * IMPORTANT — URL contract: RegistrationApi/ComplianceApi use `@POST(".")` against these base
 * URLs directly (there is no path segment beyond the base). Retrofit requires this to be the
 * exact, complete endpoint URL and it MUST end in "/" — Retrofit.Builder() throws
 * IllegalArgumentException at construction time otherwise. Do not append a path here; if the
 * real endpoint needs one, put the full path (still ending in "/") in this constant directly.
 */
object AppConfig {
    const val REGISTRATION_API_URL: String = "https://myspendingbook.com/api/public/customers/register/"
    const val COMPLIANCE_API_URL: String = "https://myspendingbook.com/api/public/customers/compliance/"

    // TODO: operator-supplied — the WhatsApp Business Cloud API account/template are still being
    // provisioned as of SPEC.md's authoring. Replace before shipping. Must end in "/".
    const val WHATSAPP_API_URL: String = "https://TODO-operator-supplied.example.com/"
    const val WHATSAPP_API_TOKEN: String = "TODO-operator-supplied"
    const val WHATSAPP_TEMPLATE_NAME: String = "TODO-operator-supplied"

    // Real, known constants (Gmail SMTP over STARTTLS) — only the account credentials are TODO.
    const val SMTP_HOST: String = "smtp.gmail.com"
    const val SMTP_PORT: Int = 587
    // TODO: operator-supplied — the Gmail account and App Password this app sends from.
    const val SMTP_USERNAME: String = "TODO-operator-supplied"
    const val SMTP_APP_PASSWORD: String = "TODO-operator-supplied"
}
