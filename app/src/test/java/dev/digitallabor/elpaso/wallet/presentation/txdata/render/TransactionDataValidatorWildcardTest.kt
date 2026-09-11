package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * PaSO View §2's array-wildcard rule, verbatim:
 *
 * > When claims contain `null` (array wildcard) in their `path`, the Wallet **SHALL**
 * > render them using the following recursive rule. For a given set of claims at a given
 * > nesting level:
 * > 1. Render all claims whose remaining path contains no `null`, in declared order.
 * > 2. Group the remaining claims by their shared path prefix up to and including the
 * >    first `null`. For each such group, in the order of its first declared claim,
 * >    iterate over the array elements at the `null` position: for each element, apply
 * >    this rule recursively to the group's claims with the `null` resolved to that
 * >    element's index.
 *
 * The order this produces is not the order the claims are declared in, and that is the
 * point: step 1 hoists every null-free claim above every expanded group, so a verifier
 * cannot interleave a scalar between two line items to change how the list reads.
 */
class TransactionDataValidatorWildcardTest {
    private fun claim(
        path: List<String?>,
        name: String? = "L",
        valueType: String? = null,
        mandatory: Boolean = false,
    ) = ClaimMetadata(
        path = path,
        mandatory = mandatory,
        valueType = valueType,
        display = if (name == null) emptyList() else listOf(ClaimDisplay("en", name, null)),
    )

    private fun validate(
        md: TransactionDataTypeMetadata,
        payload: JsonObject,
        maxRenderedItems: Int = RenderLimits.MAX_RENDERED_ITEMS,
    ): ValidationResult {
        val selection =
            LocaleSelector.select(md, listOf(Locale.ENGLISH))
                ?: return ValidationResult.Incompatible(
                    IncompatibilityReason(IncompatibilityReason.Code.NO_LOCALE_MATCH, "no locale matched"),
                )
        return TransactionDataValidator(maxRenderedItems = maxRenderedItems).validate(md, payload, selection)
    }

    private fun planOf(r: ValidationResult): RenderPlan {
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        return (r as ValidationResult.Compatible).plan
    }

    private fun reasonOf(r: ValidationResult): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        return (r as ValidationResult.Incompatible).reason.code
    }

    private fun values(plan: RenderPlan): List<String> =
        plan.rows.map {
            when (val v = it.value) {
                is RenderedValue.Text -> {
                    when (val c = v.content) {
                        is FormattedText.Plain -> c.text
                        is FormattedText.Markdown -> c.source
                    }
                }

                else -> {
                    v.toString()
                }
            }
        }

    // --- Expansion ---

    @Test
    fun expandsArrayWildcardIntoOneRowPerElement() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("items", null, "name"))), UiLabels())
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("name", JsonPrimitive("A")) })
                    add(buildJsonObject { put("name", JsonPrimitive("B")) })
                }
            }
        assertEquals(listOf("A", "B"), values(planOf(validate(md, payload))))
    }

    /** §2 step 1 runs to completion before step 2, so a null-free claim never lands between elements. */
    @Test
    fun nullFreeClaimsRenderBeforeExpandedGroups() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(listOf("items", null, "name")),
                        claim(listOf("total")),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("name", JsonPrimitive("A")) })
                    add(buildJsonObject { put("name", JsonPrimitive("B")) })
                }
                put("total", JsonPrimitive("T"))
            }
        // `total` is declared second but has no wildcard, so step 1 puts it first.
        assertEquals(listOf("T", "A", "B"), values(planOf(validate(md, payload))))
    }

    /** Within one element, the group's claims keep declared order; elements iterate outermost. */
    @Test
    fun claimsWithinAnElementKeepDeclaredOrder() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(listOf("items", null, "name")),
                        claim(listOf("items", null, "qty")),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive("A"))
                            put("qty", JsonPrimitive("1"))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive("B"))
                            put("qty", JsonPrimitive("2"))
                        },
                    )
                }
            }
        assertEquals(listOf("A", "1", "B", "2"), values(planOf(validate(md, payload))))
    }

    @Test
    fun nestedWildcardsExpandRecursively() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("a", null, "b", null, "c"))), UiLabels())
        val payload =
            buildJsonObject {
                putJsonArray("a") {
                    add(
                        buildJsonObject {
                            putJsonArray("b") {
                                add(buildJsonObject { put("c", JsonPrimitive("x")) })
                                add(buildJsonObject { put("c", JsonPrimitive("y")) })
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            putJsonArray("b") {
                                add(buildJsonObject { put("c", JsonPrimitive("z")) })
                            }
                        },
                    )
                }
            }
        assertEquals(listOf("x", "y", "z"), values(planOf(validate(md, payload))))
    }

    @Test
    fun anEmptyArrayProducesNoRows() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("items", null, "name"))), UiLabels())
        val payload = buildJsonObject { putJsonArray("items") {} }
        assertEquals(emptyList<String>(), values(planOf(validate(md, payload))))
    }

    // --- Payload coverage (Core §7.4.2 step 2) over expanded paths ---

    @Test
    fun fieldsInsideArrayElementsAreCoveredByTheirWildcardClaim() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("items", null, "name"))), UiLabels())
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("name", JsonPrimitive("A")) })
                }
            }
        assertTrue(validate(md, payload) is ValidationResult.Compatible)
    }

    /** A field the issuer never described stays uncovered even when it hides inside an array element. */
    @Test
    fun anUndeclaredFieldInsideAnArrayElementIsUncovered() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("items", null, "name"))), UiLabels())
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive("A"))
                            put("secret", JsonPrimitive("S"))
                        },
                    )
                }
            }
        assertEquals(IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED, reasonOf(validate(md, payload)))
    }

    // --- Template references (View §3) ---

    /**
     * §3: "each `null` in the referenced claim's `path` is resolved to the same array index
     * as the corresponding `null` in the referencing claim's `path`." So `{0}` inside
     * `items[1].label` must resolve to `items[1].name`, not `items[0].name`.
     */
    @Test
    fun templateReferenceBindsToTheSameArrayIndex() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(listOf("items", null, "name"), name = null),
                        claim(listOf("items", null, "label"), valueType = "template:mini_markdown"),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive("A"))
                            put("label", JsonPrimitive("Item {0}"))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive("B"))
                            put("label", JsonPrimitive("Item {0}"))
                        },
                    )
                }
            }
        assertEquals(listOf("Item A", "Item B"), values(planOf(validate(md, payload))))
    }

    /** A reference with *more* wildcards than the referencing claim has no index to bind to. */
    @Test
    fun templateReferencingADeeperWildcardIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(listOf("items", null, "name"), name = null),
                        claim(listOf("summary"), valueType = "template:mini_markdown"),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("name", JsonPrimitive("A")) })
                }
                put("summary", JsonPrimitive("First is {0}"))
            }
        assertEquals(IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE, reasonOf(validate(md, payload)))
    }

    /** Fewer wildcards is explicitly allowed: an outer scalar may be quoted inside a line item. */
    @Test
    fun templateMayReferenceAShallowerClaim() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(listOf("currency"), name = null),
                        claim(listOf("items", null, "label"), valueType = "template:mini_markdown"),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                put("currency", JsonPrimitive("EUR"))
                putJsonArray("items") {
                    add(buildJsonObject { put("label", JsonPrimitive("Paid in {0}")) })
                }
            }
        assertEquals(listOf("Paid in EUR"), values(planOf(validate(md, payload))))
    }

    // --- Interaction with the §2 item cap (Task 12) ---

    /** §2 counts "claim instances **after array wildcard expansion**", so one claim can blow the cap. */
    @Test
    fun theItemCapCountsExpandedInstancesNotClaims() {
        val md = TransactionDataTypeMetadata(listOf(claim(listOf("items", null, "name"))), UiLabels())
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    repeat(10) { i -> add(buildJsonObject { put("name", JsonPrimitive("n$i")) }) }
                }
            }
        assertEquals(10, planOf(validate(md, payload)).totalItemCount)
        assertEquals(
            IncompatibilityReason.Code.TOO_MANY_ITEMS,
            reasonOf(validate(md, payload, maxRenderedItems = 9)),
        )
    }
}
