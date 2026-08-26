package com.pagebinder.app.legal

/**
 * 初回同意で必ず表示する4点（docs/specs/12-legal-guardrails.md §3.1）。
 * 文言そのものは表示層（文字列リソース）が持ち、ここでは項目の同一性とその並びだけを定義する。
 */
enum class ConsentTerm {
    /** 正当に閲覧できるコンテンツのみを対象にすること */
    LAWFUL_CONTENT,

    /** 複製・外部サービスへの入力可否は利用者自身が確認すること */
    USER_VERIFIES_REUSE,

    /** 撮影禁止画面の回避機能はないこと */
    NO_PROTECTION_BYPASS,

    /** 出力物を無断配布しないこと */
    NO_UNAUTHORIZED_DISTRIBUTION,
}

/**
 * 表示した同意文言の版。同意履歴にはこの [version] を保存する（specs §3.1・§4）。
 * 文言を改定したら [CURRENT_CONSENT_WORDING] の version を上げる。
 */
data class ConsentWording(
    val version: String,
    val terms: List<ConsentTerm>,
)

val CURRENT_CONSENT_WORDING =
    ConsentWording(
        version = "legal-consent-v1",
        terms =
            listOf(
                ConsentTerm.LAWFUL_CONTENT,
                ConsentTerm.USER_VERIFIES_REUSE,
                ConsentTerm.NO_PROTECTION_BYPASS,
                ConsentTerm.NO_UNAUTHORIZED_DISTRIBUTION,
            ),
    )
