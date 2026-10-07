package hivens.launcher.security

/**
 * The boundary every native keyring holds: neither id may be blank.
 *
 * A blank one would not fail in the store, which is the trouble. libsecret keeps
 * an empty attribute like any other, so a secret filed under nobody sits beside
 * the real ones, and a later read under the same blank finds it.
 */
internal fun requireKeyringIds(service: String, account: String) {
    require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
}
