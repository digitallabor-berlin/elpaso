# DC API Issuance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a browser or app hand El Paso a credential to save via the W3C Digital
Credentials API, by accepting OpenID4VCI `create` requests with a pre-authorized-code grant.

**Architecture:** Register creation options with a vendored WASM matcher so the OS offers
"Save to El Paso". A new `DcIssuanceActivity` receives the `CREATE_CREDENTIAL` intent, maps
the DC API payload to a synthesized `openid-credential-offer://` URI, and enters the existing
app shell at `Route.OfferConsent`. All protocol work is done by the untouched
`IssuanceClient`.

**Tech Stack:** Kotlin, Jetpack Compose, Koin, `androidx.credentials 1.6.0-rc01`,
`androidx.credentials.registry:registry-provider 1.0.0-alpha04`,
`eudi-lib-jvm-openid4vci-kt 0.11.0`, kotlinx.serialization, JUnit + MockK.

**Spec:** `docs/superpowers/specs/2026-08-21-dc-api-issuance-design.md`

## Global Constraints

- **No dependency changes.** Every API needed already exists in the pinned versions. Do not
  bump `credentials`, `credentialsRegistry`, `eudiVci`, or `eudiVp`.
- **No `./gradlew` wrapper exists.** Use the system `gradle` (e.g.
  `gradle :app:compileDebugKotlin`). Automated hooks reporting
  `spawn ./gradlew ENOENT` are that missing wrapper, not a real failure.
- **Two permanent test failures.** `TransactionDataTest.hashEntry produces a 43-char
  base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and amount
  fields` fail on the JVM because they call `android.util.Base64`. A green run is those two
  and nothing else. Never "fix" them.
- **JVM unit-test stubbing.** `testOptions.unitTests.isReturnDefaultValues = true` makes
  everything in `android.jar` return null/0/false. That includes `android.util.*`,
  `android.net.Uri`, **and `org.json.*`**. All logic that needs a test must use
  `kotlinx.serialization.json` and `java.net.*` only.
- **New strings go in all three** of `app/src/main/res/values/strings.xml`, `values-de/`,
  `values-fr/`. `StringsParityTest` compares key sets and fails the build otherwise. Escape
  apostrophes as `\'` — aapt2 errors on a bare `'`.
- **Package root** is `dev.digitallabor.elpaso.wallet`.
- **Protocol identifiers accepted:** `openid4vci1.0`, `openid4vci-v1`, `openid4vci`.
- **Pre-authorized-code grant only.** The grant URN is exactly
  `urn:ietf:params:oauth:grant-type:pre-authorized_code`.
- **Registry id string** is exactly `openid4vci` (this is what CMWallet registers and what
  the matcher expects).
- **The version counter auto-bumps.** Any `assemble`/`bundle`/`install` task rewrites
  `app/version.properties`. Commit the bumped file like any other change.

---

### Task 1: `DcIssuanceRequest` — map a DC API request to an offer URI

This is the only piece with real branching logic, and it is pure Kotlin so it can actually
be tested. Everything it rejects is a request the rest of the flow never has to handle.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequest.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequestTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces:

```kotlin
object DcIssuanceRequest {
    const val ACK_RESPONSE_JSON: String   // """{"protocol":"openid4vci","data":{}}"""
    sealed interface Result {
        data class Offer(val offerUri: String, val issuerId: String) : Result
        data class Rejected(val reason: String) : Result
    }
    fun map(requestJson: String): Result
}
```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequestTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DcIssuanceRequestTest {

    private fun request(
        protocol: String = "openid4vci1.0",
        grants: String = """
            {"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"abc"}}
        """,
        inlineIssuer: String? = "https://issuer.example",
    ): String {
        val metadata = inlineIssuer?.let {
            ""","credential_issuer_metadata":{"credential_issuer":"$it","extra":1}"""
        } ?: ""
        return """
        {"requests":[{"protocol":"$protocol","data":{
          "credential_issuer":"https://issuer.example",
          "credential_configuration_ids":["pid"],
          "grants":$grants
          $metadata
        }}]}
        """
    }

    private fun decodedOffer(result: DcIssuanceRequest.Result): Map<String, *> {
        val offer = result as DcIssuanceRequest.Result.Offer
        val encoded = offer.offerUri.substringAfter("credential_offer=")
        return Json.parseToJsonElement(URLDecoder.decode(encoded, "UTF-8")).jsonObject
    }

    @Test
    fun `accepts all three supported protocol identifiers`() {
        listOf("openid4vci1.0", "openid4vci-v1", "openid4vci").forEach { protocol ->
            val result = DcIssuanceRequest.map(request(protocol = protocol))
            assertTrue("$protocol should be accepted", result is DcIssuanceRequest.Result.Offer)
        }
    }

    @Test
    fun `rejects an unsupported protocol`() {
        val result = DcIssuanceRequest.map(request(protocol = "openid4vp"))
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `produces an openid-credential-offer uri carrying the issuer`() {
        val result = DcIssuanceRequest.map(request())
        val offer = result as DcIssuanceRequest.Result.Offer
        assertEquals("https://issuer.example", offer.issuerId)
        assertTrue(offer.offerUri.startsWith("openid-credential-offer://?credential_offer="))
    }

    @Test
    fun `strips dc api only keys but keeps the spec offer fields`() {
        val offer = decodedOffer(DcIssuanceRequest.map(request()))
        assertTrue("credential_issuer_metadata" !in offer.keys)
        assertTrue("authorization_server_metadata" !in offer.keys)
        assertTrue("credential_issuer" in offer.keys)
        assertTrue("credential_configuration_ids" in offer.keys)
        assertTrue("grants" in offer.keys)
    }

    @Test
    fun `rejects when inline metadata issuer disagrees with the offer issuer`() {
        val result = DcIssuanceRequest.map(request(inlineIssuer = "https://evil.example"))
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `accepts when inline metadata is absent entirely`() {
        val result = DcIssuanceRequest.map(request(inlineIssuer = null))
        assertTrue(result is DcIssuanceRequest.Result.Offer)
    }

    @Test
    fun `rejects an authorization code only offer`() {
        val result = DcIssuanceRequest.map(
            request(grants = """{"authorization_code":{"issuer_state":"xyz"}}"""),
        )
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `rejects malformed json`() {
        assertTrue(DcIssuanceRequest.map("not json") is DcIssuanceRequest.Result.Rejected)
        assertTrue(DcIssuanceRequest.map("""{"nope":1}""") is DcIssuanceRequest.Result.Rejected)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*DcIssuanceRequestTest*'`
Expected: FAIL — compilation error, `DcIssuanceRequest` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Maps a W3C Digital Credentials API `create` request into a credential-offer URI the
 * existing [dev.digitallabor.elpaso.wallet.issuance.IssuanceClient] already understands.
 *
 * Deliberately pure Kotlin — no `android.net.*`, no `android.util.*`, no `org.json.*`. All
 * of those live in `android.jar` and stub to null/0/false under JVM unit tests
 * (`isReturnDefaultValues = true`), which would make this logic untestable.
 *
 * The DC API delivers issuer metadata inline; we drop it and let the EUDI library resolve
 * metadata over the network, so the whole existing issuance pipeline applies unchanged. The
 * inline copy is used only to cross-check the issuer identifier before any network call.
 */
object DcIssuanceRequest {

    /** Fixed acknowledgement returned to the caller once the credential is stored. */
    const val ACK_RESPONSE_JSON = """{"protocol":"openid4vci","data":{}}"""

    sealed interface Result {
        data class Offer(val offerUri: String, val issuerId: String) : Result

        data class Rejected(val reason: String) : Result
    }

    fun map(requestJson: String): Result {
        val root = runCatching { Json.parseToJsonElement(requestJson).jsonObject }.getOrNull()
            ?: return Result.Rejected("Request is not a JSON object")

        val requests = runCatching { root["requests"]?.jsonArray }.getOrNull()
            ?: return Result.Rejected("Request has no 'requests' array")

        val entry = requests.firstNotNullOfOrNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            val protocol = runCatching { obj?.get("protocol")?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (protocol != null && protocol in SUPPORTED_PROTOCOLS) obj else null
        } ?: return Result.Rejected("No request entry with a supported openid4vci protocol")

        val offer = runCatching { entry["data"]?.jsonObject }.getOrNull()
            ?: return Result.Rejected("Request 'data' is not a credential offer object")

        val issuerId = runCatching { offer["credential_issuer"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: return Result.Rejected("Offer has no credential_issuer")

        val inlineIssuer = runCatching {
            offer["credential_issuer_metadata"]?.jsonObject?.get("credential_issuer")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (inlineIssuer != null && inlineIssuer != issuerId) {
            return Result.Rejected("Inline metadata issuer does not match credential_issuer")
        }

        val grants = runCatching { offer["grants"]?.jsonObject }.getOrNull()
        if (grants == null || PRE_AUTHORIZED_CODE_GRANT !in grants) {
            return Result.Rejected("Only the pre-authorized_code grant is supported")
        }

        val stripped = JsonObject(offer.filterKeys { it !in DC_API_ONLY_KEYS })
        val encoded = URLEncoder.encode(stripped.toString(), "UTF-8")
        return Result.Offer(
            offerUri = "openid-credential-offer://?credential_offer=$encoded",
            issuerId = issuerId,
        )
    }

    private val SUPPORTED_PROTOCOLS = setOf("openid4vci1.0", "openid4vci-v1", "openid4vci")

    private const val PRE_AUTHORIZED_CODE_GRANT =
        "urn:ietf:params:oauth:grant-type:pre-authorized_code"

    /**
     * Present in a DC API request but not in a spec credential offer. Passing them through
     * risks the library rejecting unknown members.
     */
    private val DC_API_ONLY_KEYS =
        setOf("credential_issuer_metadata", "authorization_server_metadata")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*DcIssuanceRequestTest*'`
Expected: PASS, 8 tests.

If `rejects malformed json` fails on the `{"nope":1}` case, the cause is `root["requests"]`
returning null and `runCatching` swallowing it — confirm the elvis branch returns
`Rejected`, not a throw.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRequestTest.kt
git commit -m "feat(dcapi): map DC API create requests to credential offer URIs"
```

---

### Task 2: `DcIssuanceRegistryBlob` — build the creation-options payload

The matcher reads a packed binary blob, not JSON. Getting the offset arithmetic wrong shows
up as a missing or corrupt icon in the system sheet with no error anywhere, so it gets its
own tests.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlob.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlobTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces:

```kotlin
object DcIssuanceRegistryBlob {
    fun build(
        iconPng: ByteArray,
        title: String,
        subtitle: String?,
        issuerAllowlist: List<String>?,
    ): ByteArray
}
```

Layout is `[4-byte little-endian offset to JSON][icon PNG bytes][JSON]`, where the offset
equals `4 + iconPng.size`. A `null` allowlist omits the `capabilities` key entirely, which
the matcher treats as "offer for any issuer".

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlobTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DcIssuanceRegistryBlobTest {

    private val icon = ByteArray(64) { it.toByte() }

    private fun jsonOf(blob: ByteArray): kotlinx.serialization.json.JsonObject {
        val offset = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val text = String(blob, offset, blob.size - offset, Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `writes a little endian offset just past the icon`() {
        val blob = DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null)
        val offset = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(4 + icon.size, offset)
    }

    @Test
    fun `writes the icon bytes verbatim after the header`() {
        val blob = DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null)
        assertArrayEquals(icon, blob.copyOfRange(4, 4 + icon.size))
    }

    @Test
    fun `describes the icon location in the json`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        val iconJson = json["display"]!!.jsonObject["icon"]!!.jsonObject
        assertEquals(4, iconJson["start"]!!.jsonPrimitive.int)
        assertEquals(icon.size, iconJson["length"]!!.jsonPrimitive.int)
    }

    @Test
    fun `carries title and subtitle`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        val display = json["display"]!!.jsonObject
        assertEquals("El Paso", display["title"]!!.jsonPrimitive.content)
        assertEquals("Save it", display["subtitle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits capabilities entirely when the allowlist is null`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        assertTrue("capabilities" !in json.keys)
    }

    @Test
    fun `emits one capabilities key per allowed issuer`() {
        val json = jsonOf(
            DcIssuanceRegistryBlob.build(
                icon, "El Paso", "Save it",
                listOf("https://a.example", "https://b.example"),
            ),
        )
        val capabilities = json["capabilities"]!!.jsonObject
        assertEquals(setOf("https://a.example", "https://b.example"), capabilities.keys)
    }

    @Test
    fun `omits subtitle when null`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", null, null))
        assertTrue("subtitle" !in json["display"]!!.jsonObject.keys)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*DcIssuanceRegistryBlobTest*'`
Expected: FAIL — `DcIssuanceRegistryBlob` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlob.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the creation-options payload consumed by the vendored issuance matcher
 * (`assets/dc_issuance_matcher.wasm`).
 *
 * Binary layout, matching what the matcher's `main()` expects:
 *
 * ```text
 * [0, 4)                     little-endian int32: byte offset of the JSON
 * [4, 4 + iconPng.size)      the entry icon, PNG-encoded
 * [4 + iconPng.size, end)    UTF-8 JSON
 * ```
 *
 * The matcher shows an entry when `capabilities` is absent, or when it contains a key equal
 * to the request's `credential_issuer`. Omitting the key therefore means "any issuer".
 *
 * Pure Kotlin so the offset arithmetic is unit-testable — an off-by-four here surfaces only
 * as a silently broken icon in the system sheet.
 */
object DcIssuanceRegistryBlob {

    fun build(
        iconPng: ByteArray,
        title: String,
        subtitle: String?,
        issuerAllowlist: List<String>?,
    ): ByteArray {
        val json = buildJsonObject {
            putJsonObject("display") {
                put("title", title)
                if (subtitle != null) put("subtitle", subtitle)
                putJsonObject("icon") {
                    put("start", ICON_OFFSET)
                    put("length", iconPng.size)
                }
            }
            if (issuerAllowlist != null) {
                putJsonObject("capabilities") {
                    issuerAllowlist.forEach { issuerId -> putJsonObject(issuerId) {} }
                }
            }
        }

        val out = ByteArrayOutputStream()
        out.write(
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ICON_OFFSET + iconPng.size)
                .array(),
        )
        out.write(iconPng)
        out.write(json.toString().toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private const val HEADER_BYTES = 4
    private const val ICON_OFFSET = HEADER_BYTES
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*DcIssuanceRegistryBlobTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlob.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistryBlobTest.kt
git commit -m "feat(dcapi): build creation-options registry blob"
```

---

### Task 3: Register creation options so the OS offers the wallet

Vendors the matcher binary, adds the localised entry subtitle, and wires a registration
sync that re-registers when `developerMode` flips. Asset and strings are folded in here
because the registration is what needs them.

**Files:**

- Create: `app/src/main/assets/dc_issuance_matcher.wasm` (copied binary)
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistrySync.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt` (`dataModule`, after
  the `DcRegistrySync` line)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ElPasoApp.kt` (after
  `get<DcRegistrySync>().start(...)`)
- Test: none new — `StringsParityTest` already covers the strings by comparing key sets.

**Interfaces:**

- Consumes: `DcIssuanceRegistryBlob.build(iconPng, title, subtitle, issuerAllowlist)` from
  Task 2.
- Produces: `class DcIssuanceRegistrySync(context, settings, trustList)` with
  `fun start(scope: CoroutineScope)`.

Relevant existing signatures you will call:

```kotlin
// data/trust/TrustListService.kt
data class TrustedIssuer(val id: String, val label: String, val x5c_sha256_fingerprints: List<String>)
class TrustListService(context: Context) { fun listIssuers(): List<TrustedIssuer> }

// data/settings/SettingsRepository.kt
val developerMode: Flow<Boolean>   // defaults to true

// androidx.credentials.registry.provider (alpha04) — constructor order is
// (type, id, creationOptions, matcher, intentAction); registerCreationOptions is suspend
abstract class RegisterCreationOptionsRequest(
    type: String, id: String, creationOptions: ByteArray, matcher: ByteArray, intentAction: String,
)
```

- [ ] **Step 1: Vendor the matcher binary**

```bash
cp ~/dev/eudiw/CMWallet/app/src/main/assets/provision_hardcoded.wasm \
   app/src/main/assets/dc_issuance_matcher.wasm
ls -l app/src/main/assets/dc_issuance_matcher.wasm
```

Expected: about 56376 bytes. Use `provision_hardcoded.wasm`, **not** `provision.wasm` — the
latter recognises only `openid4vci1.0` and would ignore `openid4vci-v1`. Neither embeds
CMWallet branding; both read title/subtitle/icon from our blob.

- [ ] **Step 2: Add the entry subtitle string to all three locales**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="dc_issuance_save_subtitle">Save your document to El Paso</string>
```

In `app/src/main/res/values-de/strings.xml`:

```xml
<string name="dc_issuance_save_subtitle">Dokument in El Paso speichern</string>
```

In `app/src/main/res/values-fr/strings.xml`:

```xml
<string name="dc_issuance_save_subtitle">Enregistrer votre document dans El Paso</string>
```

- [ ] **Step 3: Run the strings parity test to verify all three locales agree**

Run: `gradle :app:testDebugUnitTest --tests '*StringsParityTest*'`
Expected: PASS. A failure names the locale missing the key.

- [ ] **Step 4: Write `DcIssuanceRegistrySync`**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistrySync.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.DigitalCredential
import androidx.credentials.registry.provider.RegisterCreationOptionsRequest
import androidx.credentials.registry.provider.RegistryManager
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Registers the wallet as a Digital Credentials API *issuance* target, so a browser calling
 * `navigator.credentials.create()` with an OpenID4VCI request is offered "Save to El Paso".
 *
 * Deliberately separate from [DcRegistrySync]: that one re-registers whenever the credential
 * set changes, whereas creation options depend only on [SettingsRepository.developerMode] and
 * the static trust list.
 *
 * The allowlist mirrors the in-app trust gate in `IssuanceClient.resolveOffer`
 * (`developerMode || isIssuerTrusted(issuerId)`) so the two cannot disagree. In developer
 * mode the `capabilities` key is omitted, which the matcher reads as "any issuer"; untrusted
 * issuers are then warned about in-app rather than hidden. With developer mode off, only
 * trusted issuers can see the wallet at all.
 */
class DcIssuanceRegistrySync(
    private val context: Context,
    private val settings: SettingsRepository,
    private val trustList: TrustListService,
) {
    private val registryManager by lazy { RegistryManager.create(context) }

    private val matcherWasm: ByteArray by lazy {
        context.assets.open(MATCHER_ASSET).use { it.readBytes() }
    }

    /**
     * Re-registers on every [SettingsRepository.developerMode] change. The flow emits its
     * current value on collection, so this also performs the initial registration.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.developerMode.distinctUntilChanged().collect { developerMode ->
                register(developerMode)
            }
        }
    }

    private suspend fun register(developerMode: Boolean) {
        val allowlist = if (developerMode) null else trustList.listIssuers().map { it.id }
        val blob =
            DcIssuanceRegistryBlob.build(
                iconPng = launcherIconPng(),
                title = context.getString(R.string.app_name),
                subtitle = context.getString(R.string.dc_issuance_save_subtitle),
                issuerAllowlist = allowlist,
            )
        Log.i(
            LOG_TAG,
            "Registering creation options developer_mode=$developerMode " +
                "allowlist=${allowlist?.size ?: "any"} blob_bytes=${blob.size} " +
                "matcher_bytes=${matcherWasm.size}",
        )
        // Creation-options support depends on the device's Play Services version. An
        // unsupported device must degrade to "the wallet is not offered", never a crash.
        runCatching {
            registryManager.registerCreationOptions(
                object : RegisterCreationOptionsRequest(
                    type = DigitalCredential.TYPE_DIGITAL_CREDENTIAL,
                    id = REGISTRY_ID,
                    creationOptions = blob,
                    matcher = matcherWasm,
                    intentAction = "",
                ) {},
            )
        }.onSuccess { Log.i(LOG_TAG, "registerCreationOptions succeeded") }
            .onFailure { Log.w(LOG_TAG, "registerCreationOptions failed", it) }
    }

    private fun launcherIconPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { drawable ->
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private companion object {
        const val LOG_TAG = "DcIssuanceRegistry"

        /** Must match what the matcher and CMWallet register under. */
        const val REGISTRY_ID = "openid4vci"
        const val ICON_PX = 96
        const val MATCHER_ASSET = "dc_issuance_matcher.wasm"
    }
}
```

- [ ] **Step 5: Register it in Koin**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`, inside `dataModule`,
immediately after the existing `single { DcRegistrySync(get(), get()) }`:

```kotlin
single { DcIssuanceRegistrySync(get(), get(), get()) }
```

Add the import `dev.digitallabor.elpaso.wallet.dcapi.DcIssuanceRegistrySync` alongside the
existing `DcRegistrySync` import. The three `get()`s resolve to `Context`,
`SettingsRepository` and `TrustListService`, all already registered.

- [ ] **Step 6: Start it on app launch**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/ElPasoApp.kt`, directly after the
existing line:

```kotlin
get<DcRegistrySync>().start(lifecycleOwner.lifecycleScope)
```

add:

```kotlin
get<DcIssuanceRegistrySync>().start(lifecycleOwner.lifecycleScope)
```

Add the matching import `dev.digitallabor.elpaso.wallet.dcapi.DcIssuanceRegistrySync`.

- [ ] **Step 7: Verify it compiles**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

If `RegisterCreationOptionsRequest` reports a `cannot access '<init>'` error, confirm the
`object :` form is used — the class is abstract and cannot be constructed directly.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/assets/dc_issuance_matcher.wasm \
        app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceRegistrySync.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/ElPasoApp.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-de/strings.xml \
        app/src/main/res/values-fr/strings.xml
git commit -m "feat(dcapi): register creation options for openid4vci issuance"
```

---

### Task 4: Thread the DC API callbacks through the offer-consent route

`WalletAppRoot` already accepts DC API callbacks but only `Route.Present` honours them. The
`Route.OfferConsent` branch hardcodes navigation to Home, so a credential-manager-hosted
activity would have no way to learn the flow finished.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/WalletApp.kt` (the
  `WalletAppRoot` parameter list around line 70, and the `is Route.OfferConsent` branch
  around line 162)

**Interfaces:**

- Consumes: nothing.
- Produces: `WalletAppRoot(startRoute, onDcApiResult, onDcApiCancel, onDcApiError, onDcApiIssuanceDone)`
  — the new fifth parameter is `onDcApiIssuanceDone: (() -> Unit)? = null`.

- [ ] **Step 1: Add the parameter**

Change the signature from:

```kotlin
fun WalletAppRoot(
    startRoute: Route,
    onDcApiResult: ((responseJson: String) -> Unit)? = null,
    onDcApiCancel: (() -> Unit)? = null,
    onDcApiError: ((message: String) -> Unit)? = null,
) {
```

to:

```kotlin
fun WalletAppRoot(
    startRoute: Route,
    onDcApiResult: ((responseJson: String) -> Unit)? = null,
    onDcApiCancel: (() -> Unit)? = null,
    onDcApiError: ((message: String) -> Unit)? = null,
    // Issuance returns a fixed acknowledgement rather than a computed response document,
    // so it gets its own no-argument callback instead of reusing onDcApiResult. Keeping the
    // ack in DcIssuanceActivity avoids a ui/ -> dcapi/ import.
    onDcApiIssuanceDone: (() -> Unit)? = null,
) {
```

- [ ] **Step 2: Thread both callbacks in the `Route.OfferConsent` branch**

Change:

```kotlin
                    is Route.OfferConsent -> {
                        AddOfferFlow(
                            modifier = Modifier.padding(inner),
                            incomingOfferUri = r.offerUri,
                            onDone = { current = Route.Home },
                            onCancel = { current = Route.Home },
                        )
                    }
```

to:

```kotlin
                    is Route.OfferConsent -> {
                        AddOfferFlow(
                            modifier = Modifier.padding(inner),
                            incomingOfferUri = r.offerUri,
                            onDone = {
                                if (onDcApiIssuanceDone != null) {
                                    onDcApiIssuanceDone()
                                } else {
                                    current = Route.Home
                                }
                            },
                            onCancel = {
                                if (onDcApiCancel != null) onDcApiCancel() else current = Route.Home
                            },
                        )
                    }
```

In-app deep-link issuance is unaffected: both callbacks are null there, so the branch still
navigates to `Route.Home`, exactly as before.

- [ ] **Step 3: Verify it compiles and nothing regressed**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `gradle :app:testDebugUnitTest --tests '*AddOfferScreenTest*'`
Expected: PASS — the existing offer-screen test must still pass, proving the in-app path is
untouched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/ui/WalletApp.kt
git commit -m "feat(ui): thread DC API callbacks through the offer consent route"
```

---

### Task 5: `DcIssuanceActivity` — receive the CREATE_CREDENTIAL intent

The provider entry point. It mirrors `DcPresentationActivity` and must enter through
`WalletAppRoot` rather than hosting `AddOfferFlow` directly, because `WalletAppRoot` is what
enforces the app lock.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (beside the existing `.dcapi.DcPresentationActivity`
  entry)

**Interfaces:**

- Consumes: `DcIssuanceRequest.map(requestJson)`, `DcIssuanceRequest.ACK_RESPONSE_JSON`,
  `DcIssuanceRequest.Result.{Offer,Rejected}` (Task 1);
  `WalletAppRoot(startRoute, onDcApiIssuanceDone, onDcApiCancel)` (Task 4);
  existing `Route.OfferConsent(offerUri: String?)` and `IssuanceClient.reset()`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the activity**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceActivity.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.credentials.CreateDigitalCredentialResponse
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import dev.digitallabor.elpaso.wallet.ui.WalletAppRoot
import dev.digitallabor.elpaso.wallet.ui.nav.Route
import dev.digitallabor.elpaso.wallet.ui.theme.ElPasoTheme
import org.koin.android.ext.android.get

/**
 * Entry point when the user picks "Save to El Paso" from the system credential-creation
 * sheet, i.e. a `navigator.credentials.create()` call carrying an OpenID4VCI credential
 * offer.
 *
 * The offer is mapped to an `openid-credential-offer://` URI and handed to the ordinary
 * consent flow at [Route.OfferConsent], so issuance is performed by the same
 * [IssuanceClient] pipeline the deep-link path uses.
 *
 * Entry goes through [WalletAppRoot] rather than `AddOfferFlow` directly on purpose:
 * `WalletAppRoot` owns `AppLockManager` and shows the lock screen when the wallet is locked.
 * Hosting the consent screen directly would let a DC API caller drive issuance past the app
 * lock.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class DcIssuanceActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(LOG_TAG, "onCreate action=${intent?.action} has_extras=${intent?.extras != null}")

        val request = runCatching {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        }.getOrNull()
        if (request == null) {
            Log.w(LOG_TAG, "No ProviderCreateCredentialRequest in intent; aborting")
            finishWithException("Missing create credential request")
            return
        }

        val requestJson = runCatching {
            request.callingRequest.credentialData.getString(BUNDLE_KEY_REQUEST_JSON)
        }.getOrNull()
        if (requestJson.isNullOrEmpty()) {
            Log.w(LOG_TAG, "Create request carried no request JSON; aborting")
            finishWithException("Missing request JSON")
            return
        }

        Log.i(
            LOG_TAG,
            "create request calling_package=${request.callingAppInfo.packageName} " +
                "json_len=${requestJson.length}",
        )

        when (val mapped = DcIssuanceRequest.map(requestJson)) {
            is DcIssuanceRequest.Result.Rejected -> {
                Log.w(LOG_TAG, "rejected: ${mapped.reason}")
                finishWithException(mapped.reason)
            }

            is DcIssuanceRequest.Result.Offer -> {
                Log.i(LOG_TAG, "accepted issuer=${mapped.issuerId}")
                // IssuanceClient is a singleton holding one session. Concurrent in-app and
                // DC API issuance is unsupported; clear any stale state so this flow starts
                // from Idle rather than inheriting an abandoned one.
                runCatching { get<IssuanceClient>().reset() }
                setContent {
                    ElPasoTheme {
                        WalletAppRoot(
                            startRoute = Route.OfferConsent(mapped.offerUri),
                            onDcApiIssuanceDone = { finishWithSuccess() },
                            onDcApiCancel = { finishWithCancellation() },
                        )
                    }
                }
            }
        }
    }

    private fun finishWithSuccess() {
        Log.i(LOG_TAG, "finishWithSuccess")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            data,
            CreateDigitalCredentialResponse(DcIssuanceRequest.ACK_RESPONSE_JSON),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithException(message: String) {
        Log.w(LOG_TAG, "finishWithException: $message")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialException(
            data,
            CreateCredentialUnknownException(message),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithCancellation() {
        Log.i(LOG_TAG, "finishWithCancellation")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialException(
            data,
            CreateCredentialCancellationException(),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private companion object {
        const val LOG_TAG = "DcIssuanceActivity"

        /** Bundle key Credential Manager uses to carry the request JSON. */
        const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }
}
```

- [ ] **Step 2: Declare the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, directly after the existing
`.dcapi.DcPresentationActivity` block:

```xml
<activity
    android:name=".dcapi.DcIssuanceActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@style/Theme.ElPaso">
    <intent-filter>
        <action android:name="androidx.credentials.registry.provider.action.CREATE_CREDENTIAL" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

- [ ] **Step 3: Verify it compiles**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

If `CreateDigitalCredentialResponse` is unresolved, confirm the import is
`androidx.credentials.CreateDigitalCredentialResponse` (the core artifact) and that the class
is annotated `@OptIn(ExperimentalDigitalCredentialApi::class)`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcIssuanceActivity.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(dcapi): add DcIssuanceActivity for CREATE_CREDENTIAL requests"
```

---

### Task 6: Documentation correction and full verification gate

**Files:**

- Modify: `AGENTS.md` (the `SettingsRepository` bullet under "Cross-cutting architecture")
- Modify: `README.md` (the assets list — around line 304)

**Interfaces:**

- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Correct the AGENTS.md settings claim**

AGENTS.md currently claims `SettingsRepository` is the source of truth for "theme, language,
wallet order, metadata-cache enable + TTL, developer mode, and DC API registration". There
is no DC API registration flag. Replace that bullet's list with the real one and note the new
dependency:

```markdown
- **`SettingsRepository` is the single source of truth** for theme, language, wallet
  order, metadata-cache enable + TTL, and developer mode. DataStore Preferences
  underneath. Do not add a parallel preference store. Note there is no "DC API
  registration" flag: `DcRegistrySync` registers unconditionally, and
  `DcIssuanceRegistrySync` keys only off `developerMode` (which selects whether the
  creation-options matcher gets an issuer allowlist).
```

- [ ] **Step 2: Document the vendored asset and its provenance**

In `README.md`, extend the assets line so the new binary is not mistaken for something we
built:

```markdown
Assets: `dcapi_matcher.wasm` (DC API presentation matcher binary),
`dc_issuance_matcher.wasm` (DC API issuance/creation-options matcher, vendored from
CMWallet's `provision_hardcoded.wasm`; source is CMWallet `matcher/issuance/provision.c`),
`trusted_issuers.json`, `trusted_verifiers.json`
```

- [ ] **Step 3: Run the full unit test suite**

Run: `gradle :app:testDebugUnitTest`
Expected: the 2 known `TransactionDataTest` failures and **nothing else**. Task 1 adds 8
tests and Task 2 adds 7, so the total should be 71 with 2 failures.

If any other test fails, stop and fix it before continuing — a new failure here means one of
the earlier tasks regressed something.

- [ ] **Step 4: Run the R8 release gate**

Run: `gradle :app:assembleRelease`
Expected: BUILD SUCCESSFUL. This is the only build that exercises ProGuard/R8, and this
change adds a manifest-registered activity plus reflective registry APIs.

Manifest-declared activities are kept by R8 automatically, so no `proguard-rules.pro` change
is expected. If R8 strips something, add the keep rule and note why in the commit.

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md README.md app/version.properties
git commit -m "docs: correct settings claim and record vendored issuance matcher"
```

- [ ] **Step 6: Manual device verification**

This cannot be automated and is the only proof the feature actually works. On a device whose
Play Services supports creation options:

1. Install: `gradle :app:installDebug`
2. Confirm registration succeeded: `adb logcat -s DcIssuanceRegistry` should show
   `registerCreationOptions succeeded`. If it shows a failure, the device's Play Services is
   too old — the wallet simply will not be offered, which is the intended degradation.
3. From Chrome, call `navigator.credentials.create()` with a pre-authorized-code offer from
   the EUDIPLO test-tenant issuer and confirm "Save to El Paso" appears with the app icon.
4. Complete the flow (entering the tx_code if the offer requires one) and confirm the
   credential appears on the home deck and is immediately presentable via an OpenID4VP
   request — that proves `DcRegistrySync` re-registered it.
5. Re-run and cancel at the consent screen; confirm the browser sees a cancellation rather
   than a hang or a success.
6. Lock the wallet, then trigger the flow again and confirm the lock screen appears **before**
   the consent screen.
7. Turn developer mode off in Settings, then trigger a create request from an issuer absent
   from `trusted_issuers.json` and confirm El Paso is **not** offered.

---

## Notes for the executor

- **Task order matters.** Tasks 1 and 2 are independent and could run in parallel. Task 3
  needs Task 2, Task 5 needs Tasks 1 and 4. Task 6 is last.
- **Do not touch `IssuanceClient`, `AddOfferFlow`, `OfferHandler`, `Route`, or
  `DcRegistrySync`.** The design depends on those being unchanged; if you find yourself
  wanting to edit one, the plan is wrong — stop and raise it.
- **The `authorization_code` grant is out of scope.** `DcIssuanceRequest` rejects it, which
  is what keeps `IssuanceClient.State.AwaitingAuth` unreachable from this entry point. Do
  not "improve" this by allowing it; `AwaitingAuth` launches an external browser and returns
  through `MainActivity`, which would strand this activity.
