package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
 * wildcards have been bound to concrete indices — is [ResolvedPath] below, which arrived
 * with wildcard expansion and does earn its keep.
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

/**
 * One step of a path whose wildcards have been bound to actual array positions.
 *
 * A declared `path` is a *pattern*: `["items", null, "amount"]` describes every
 * `items[i].amount`. Rendering needs the other thing — one concrete location per row —
 * because PaSO View §2 produces a separate claim instance per array element, and each
 * instance has its own value, its own `#integrity` sibling, and its own index binding for
 * template references. Keeping the two as distinct types is what stops the pattern being
 * used where a location is required; the bug it prevents is `filterNotNull()`, which
 * silently turns `items.[].amount` into the path `items.amount` that matches nothing.
 */
sealed interface PathStep {
    data class Key(
        val key: String,
    ) : PathStep

    data class Index(
        val at: Int,
    ) : PathStep
}

/** A claim path with every wildcard resolved to a concrete array index. */
typealias ResolvedPath = List<PathStep>

/** Reads the value at a fully-resolved location, or null if any step is absent or mistyped. */
fun resolveValue(
    root: JsonObject,
    path: ResolvedPath,
): JsonElement? {
    var node: JsonElement = root
    for (step in path) {
        node =
            when (step) {
                is PathStep.Key -> (node as? JsonObject)?.get(step.key) ?: return null
                is PathStep.Index -> (node as? JsonArray)?.getOrNull(step.at) ?: return null
            }
    }
    return node
}

/**
 * Whether [pattern] — a declared claim path, in which `null` matches any array index —
 * covers this concrete location, i.e. equals it or is a prefix of it.
 *
 * This is the wildcard-aware form of the containment test PaSO Core §7.4.2 step 2 needs:
 * a claim on `items.[].amount` covers the payload field `items.0.amount`, and a claim on
 * `payee` covers everything beneath `payee`.
 */
fun ResolvedPath.isCoveredBy(pattern: List<String?>): Boolean = pattern.size <= size && agreesWith(pattern)

/**
 * Whether this location is a strict *ancestor* of [pattern] — a container the issuer
 * declared claims beneath, such as `items` when the claim is `items.[].amount`.
 *
 * Being an ancestor is not on its own enough to make a field legitimate: a scalar sitting
 * where the issuer expected an object holds data no claim can render. The caller pairs
 * this with an emptiness test, so an empty `items` array passes and `a.b = 1` under a
 * declared `a.b.c` does not.
 */
fun ResolvedPath.leadsTo(pattern: List<String?>): Boolean = size < pattern.size && agreesWith(pattern)

/** Whether this location and [pattern] agree on every step the two of them share. */
private fun ResolvedPath.agreesWith(pattern: List<String?>): Boolean {
    for (i in 0 until minOf(size, pattern.size)) {
        val segment = pattern[i]
        val matches =
            when (val step = this[i]) {
                is PathStep.Index -> segment == null
                is PathStep.Key -> segment != null && step.key == segment
            }
        if (!matches) return false
    }
    return true
}

/**
 * `items.[0].amount` — a concrete location for diagnostics.
 *
 * `@JvmName` because [List.renderKey] above erases to the same JVM signature: both are
 * extensions on `List`, and generics do not survive to the bytecode.
 */
@JvmName("describeResolvedPath")
fun ResolvedPath.describe(): String =
    joinToString(".") { step ->
        when (step) {
            is PathStep.Key -> step.key
            is PathStep.Index -> "[${step.at}]"
        }
    }
