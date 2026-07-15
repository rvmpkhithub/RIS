package com.ris.imagedistributor.config

import com.ris.imagedistributor.BuildConfig

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

    // Real, known constants (Gmail SMTP over STARTTLS) — only the account credentials are secret.
    const val SMTP_HOST: String = "smtp.gmail.com"
    const val SMTP_PORT: Int = 587
    // Real credentials (2026-07-14/15 addendum) — read from local.properties at build time via
    // BuildConfig, never hardcoded here. This file is tracked in git; local.properties is not.
    // See local.properties.example for the keys and setup instructions. Falls back to the
    // pre-existing "TODO-operator-supplied" placeholder if local.properties doesn't set them —
    // syntactically valid, fails at runtime (a bad SMTP login) rather than at build time, same as
    // this project's other not-yet-provisioned secrets (WHATSAPP_* above).
    val SMTP_USERNAME: String = BuildConfig.SMTP_USERNAME
    val SMTP_APP_PASSWORD: String = BuildConfig.SMTP_APP_PASSWORD
}
