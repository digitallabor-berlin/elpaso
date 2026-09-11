package dev.digitallabor.elpaso.wallet.presentation.txdata.render

/**
 * Helpers over a claim metadata `path`.
 *
 * `ClaimMetadata.path` is `List<String?>`: a non-null segment is a JSON object key, and a
 * **`null` segment is an array wildcard** per PaSO View §2, matching every element at that
 * position. `kotlinx.serialization` decodes a JSON `null` array element straight into a
 * Kotlin `String?`, so the wire format and the domain type line up without a mapper.
 *
 * A sealed `PathSegment` type was considered and rejected: it would have touched every
 * call site for no behavioural gain. The richer type that *is* needed — a path whose
 * wildcards have been bound to concrete indices — arrives with wildcard expansion, where
 * it earns its keep.
 */

/** Number of array-wildcard segments. Used by the template reference rule (View §3). */
fun List<String?>.wildcardCount(): Int = count { it == null }

fun List<String?>.hasWildcard(): Boolean = any { it == null }

/**
 * A stable, human-readable key for this path, rendering each wildcard as `[]` —
 * `["items", null, "amount"]` becomes `items.[].amount`.
 *
 * Used for duplicate-path detection (§3.3 forbids two claims sharing a `path`), for
 * diagnostics, and as a last-resort label when a claim's `display` entry carries no
 * `name`. It is a display/identity key, not a lookup key: resolving a value needs the
 * wildcards bound to indices.
 */
fun List<String?>.renderKey(): String = joinToString(".") { it ?: "[]" }
