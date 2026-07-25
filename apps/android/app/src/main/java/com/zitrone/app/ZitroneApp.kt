// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import android.app.Application
import android.util.Log
import com.goterl.lazysodium.SodiumAndroid
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.LemonDropSodiumOps
import com.zitrone.app.crypto.SignalProtocolManager
import com.zitrone.app.crypto.VaultSignalProtocolStore
import com.zitrone.app.crypto.ZitroneSignalStore
import com.zitrone.app.crypto.vault.BiometricVaultKeyCipher
import com.zitrone.app.crypto.vault.KeystoreDeviceKeyCipher
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.ReconcileResult
import com.zitrone.app.crypto.vault.ResidueSweepResult
import com.zitrone.app.crypto.vault.VaultImageStore
import com.zitrone.app.crypto.vault.UnlockOrAdd
import com.zitrone.app.crypto.vault.VaultImageException
import com.zitrone.app.crypto.vault.VaultOpen
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultSodiumOps
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.wipe
import com.zitrone.app.data.BiometricUnlockStore
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.DeviceSettings
import com.zitrone.app.data.LemonDropCreator
import com.zitrone.app.data.LemonDropRedeemer
import com.zitrone.app.data.LemonDropScanOutcome
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.data.MessageRepository
import com.zitrone.app.data.MessageState
import com.zitrone.app.data.SettingsRepository
import com.zitrone.app.data.TransportState
import com.zitrone.app.data.VaultAuthStore
import com.zitrone.app.data.VaultRosterStore
import com.zitrone.app.data.VaultSettingsStore
import com.zitrone.app.diagnostics.BootDiagnostics
import com.zitrone.app.i2p.I2pIntegration
import com.zitrone.app.net.ApiClient
import com.zitrone.app.net.CertificatePinning
import com.zitrone.app.net.HttpConnectI2pProber
import com.zitrone.app.net.TransportResolver
import com.zitrone.app.net.WsClient
import com.zitrone.app.notifications.MessagingNotifications
import com.zitrone.app.notifications.NotificationScheduler
import com.zitrone.app.tor.TorIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Application entry point. No analytics, no crash reporting, no telemetry —
 * the only thing initialized here is the dependency graph and the
 * content-free notification channel.
 */
class ZitroneApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MessagingNotifications.ensureChannel(this)
    }
}

/**
 * Hand-rolled dependency container — deliberately no DI framework, so the
 * complete object graph of a privacy-critical app stays auditable in one file.
 *
 * The graph is split along a device/session seam (P1b-2 PR-D1):
 *  - `AppContainer` is the DEVICE half — process-lifetime, readable pre-unlock:
 *    the scope, keystore, [DeviceSettings], the transport stack, boot
 *    diagnostics, the lemon-drop veil, AND the vault device-layer ([imageStore] +
 *    [biometricCipher]) that survives lock/unlock cycles.
 *  - [SessionContainer] is the SESSION half — the messaging objects that live
 *    only while a slot is unlocked, now backed by the vault runtime.
 *
 * PR-D2c makes the app VAULT-ONLY (maintainer decision: zero users/accounts exist,
 * so there is no migration constituency). Routing truth is [hasVault]
 * (`imageStore.exists()`): no image → vault SETUP (onboarding passphrase → create),
 * image present → vault UNLOCK (passphrase always; biometric iff enabled). There is
 * NO silent auto-unlock and no fail-open — every unlock is passphrase-or-biometric.
 * The legacy store CLASSES stay in the tree (still compiled + test-covered); only
 * the runtime WIRING here is the vault path.
 */

/**
 * The outcome of a passphrase entry through [AppContainer.attemptPassphrase] (0.9.2 second-vault
 * router). SLOT-AGNOSTIC: the UI learns only which of these happened, never which slot or how many
 * vaults exist. [Rejected] is indistinguishable (behaviour + timing) from a wrong passphrase, and it
 * is the outcome a create-refused-because-a-delete-is-pending also returns (fail-closed).
 */
sealed interface PassphraseOutcome {
    /** An existing vault slot matched — a session was published. Route to the chat. */
    data object Unlocked : PassphraseOutcome

    /** No slot matched, the triple-entry gate fired, a new vault was created + published. */
    data object Created : PassphraseOutcome

    /** The Pucker Burn slot matched — the app performs the duress wipe (a sibling feature). */
    data object Burn : PassphraseOutcome

    /** No match (or a refused build, or a fail-closed create): a wrong-passphrase equivalent. */
    data object Rejected : PassphraseOutcome

    /** The stored image is present but unreadable (corrupt / missing DEK) — a distinct honest error. */
    data object ImageUnreadable : PassphraseOutcome

    /** The stored image is a prior (v2 / 0.9.1) format — route to fresh onboarding (it retires there). */
    data object LegacyImage : PassphraseOutcome

    /** A create wrote but its durability was unconfirmed — surface a generic retry (a re-entry recovers). */
    data object Retry : PassphraseOutcome
}

class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStoreManager = KeyStoreManager(app)

    // Legacy settings store — still the single source of truth for DEVICE-level
    // settings and, on ALL D2c paths, the vault-scoped fields too (D5 moves the
    // latter into the vault; D2c keeps them on prefs to avoid a split-brain).
    val settingsRepository = SettingsRepository(keyStoreManager)

    /** Device-scoped, pre-unlock settings view over the SAME legacy store. */
    val deviceSettings = DeviceSettings(settingsRepository)

    // ── Vault device layer (process lifetime; survives lock/unlock) ────────────

    /** Portable AES-256-GCM + Argon2id backend, shared by the image store and every session. */
    private val vaultOps: VaultSodiumOps = LibsodiumVaultOps(SodiumAndroid())

    /**
     * The ONE device-level image store for this install (single-instance-per-baseDir
     * contract). Held open for the process lifetime across lock/unlock — the outer
     * device-key layer is not a slot secret, so keeping it open is fine, and a fresh
     * unlock reuses this instance rather than re-registering the directory.
     */
    /**
     * The device-key cipher, held as a field rather than constructed inline so the BURN path can
     * reach it — its alias is lazily created and therefore an oracle (see [wipeBiometricMaterial]
     * and `KeystoreDeviceKeyCipher.deleteKeyMaterial`).
     */
    private val deviceKeyCipher = KeystoreDeviceKeyCipher()

    val imageStore = VaultImageStore(app.filesDir, vaultOps, deviceKeyCipher)

    /** The auth-gated biometric key that wraps the slot-A vault key (dual-wrap, posture B). */
    val biometricCipher = BiometricVaultKeyCipher()

    /** Persisted `{ slotIndex, aliasId, wrappedVaultKey }` — present ONLY for a biometric-enabled install. */
    val biometricStore = BiometricUnlockStore(keyStoreManager)

    /**
     * Serializes EVERY biometric-wrap-state mutation — enable-commit (belt re-check + alias-exists check
     * + save), disable, account-delete cleanup, and the cold-start alias GC — so INV-1 holds under
     * arbitrary interleaving. Without it, a disable/GC could reap the alias an in-flight enable is about
     * to reference (orphan), and two cross-slot first-enables could both commit (silent rebind). The
     * enable-commit runs the never-repoint belt AND `keyExists(aliasId)` under this lock, so a concurrent
     * delete makes it ABORT instead of persisting a wrap that references a gone key.
     */
    private val biometricWriteLock = Any()

    /** Composable-free unlock decisions (backoff, uniform failure, biometric gating). */
    val unlockRouter = VaultUnlockRouter()

    /**
     * Whether any Activity is STARTED (foreground-visible) — set from MainActivity's
     * onStart/onStop. Process-scoped so process-scoped coroutines can gate user-visible
     * publishes (the lemon-drop Delivered veil) WITHOUT capturing an Activity — capturing one
     * leaks the destroyed instance across rotation for the coroutine's lifetime and gates on a
     * stale lifecycle. @Volatile: written on main, read from worker dispatchers.
     */
    @Volatile
    var activityStarted: Boolean = false

    /**
     * Single-flight vault-creation state, PROCESS-scoped and OBSERVABLE (round 11, Gemini): the
     * composable's own flag resets on rotation while the Argon2 create keeps running, so a
     * composition-local guard would let a second tap start a concurrent create — and a plain
     * seeded bool would strand the recreated spinner if the create then failed. The UI collects
     * this; [tryBeginVaultCreate] claims (compare-and-set), [endVaultCreate] releases.
     */
    val vaultCreating = MutableStateFlow(false)

    fun tryBeginVaultCreate(): Boolean = vaultCreating.compareAndSet(expect = false, update = true)

    fun endVaultCreate() {
        vaultCreating.value = false
    }

    /**
     * PROCESS-scoped single-flight for a passphrase unlock attempt. The lock screen's `unlocking`
     * guard is COMPOSITION-local, so it resets to false on an Activity recreation (rotation) and
     * cannot stop the recreated screen from launching a SECOND [attemptPassphrase] while a
     * rotation-cancelled first one's UNINTERRUPTIBLE store call (holding `imageLock`) is still
     * finishing. That overlap let the cancelled attempt's triple-entry streak be READ + advanced by
     * the next entry (latching `create=true`) BEFORE the cancelled attempt's [resetCandidate] landed
     * — creating after fewer than 3 uninterrupted entries (paired-blind review round 5, BOTH
     * reviewers). This flag is process-scoped (survives recreation) so [attemptPassphrase] serializes
     * end-to-end: a cancelled attempt's rollback completes before any next attempt can read the
     * streak. Reject-based, mirroring [tryBeginVaultCreate]; no UI observation needed (the
     * composition-local `unlocking` already drives the spinner). RAM-only → process death clears it.
     */
    private val unlockInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryBeginUnlock(): Boolean = unlockInFlight.compareAndSet(false, true)

    fun endUnlock() {
        unlockInFlight.set(false)
    }

    /** Routing truth: a vault image is present → UNLOCK, absent → SETUP (onboarding). */
    fun hasVault(): Boolean = imageStore.exists()

    /**
     * FAIL-CLOSED routing truth: "there is provably nothing here", the only state that may present as
     * a fresh install. [hasVault] is NOT a substitute — it keys on `vault.bin` alone, so a surviving
     * `vault.dek` or `vault.bin.tmp` (which stages a COMPLETE outer image) reads as "no vault" and
     * would route ONBOARDING over recoverable ciphertext.
     */
    fun vaultProvenAbsent(): Boolean = imageStore.imageBearingProvenAbsent()

    /**
     * Read the four disk facts and produce ONE boot decision — the single derivation every routing
     * consumer uses.
     *
     * SUSPEND, and it moves itself to IO (round-2 review, Gemini). This was a plain function with a
     * kdoc saying "call off the main thread", and one of its three callers — the session collector —
     * called it bare on the composition dispatcher, running a ~1 MiB decrypt on the main thread. A
     * requirement stated in a comment is a requirement that will eventually be violated by one call
     * site; the dispatcher move belongs INSIDE, where no caller can get it wrong. Callers now simply
     * `deriveBootDecisionFromDisk()`.
     */
    internal suspend fun deriveBootDecisionFromDisk(
        supersedeCompletedDestroy: Boolean = false,
    ): BootDecision = withContext(Dispatchers.IO) {
        // ONE classification, not two independently-timed reads. [hasVault] and [vaultProvenAbsent]
        // each take the image lock separately, so calling them as a pair could pair up readings taken
        // at different instants — including the contradiction "present AND proven absent", which
        // [Residence] cannot represent. The mapping below is DELIBERATELY the identity of today's
        // semantics: Present ⇔ `hasVault()`, ProvenAbsent ⇔ `vaultProvenAbsent()`.
        //
        // DO NOT "simplify" this to `imagePresent = residence.treatAsPresent`. It looks equivalent
        // and is not: `bootRoute` orders the LEGACY arm AHEAD of the present arm, and legacy routes
        // to ONBOARDING. Mapping Indeterminate onto imagePresent would send an image that cannot be
        // stat'd into the ~1 MiB legacy probe, and a `true` there would present a fresh install over
        // exactly the unprovable material this type exists to withhold. Indeterminate must fall
        // through to the LOCKED arm, which is what leaving BOTH booleans false does.
        val residence = vaultResidence()
        val confirmed = serverDeleteConfirmed()
        // THE SUPERSEDE DECISION LIVES HERE, not at the call site (0.9.2 Unit W-B, items #1 + #5).
        //
        // The delete-completion callback used to take TWO fresh stats of its own to decide this and
        // then call this function, which stats the disk AGAIN — three defects in one place: disk I/O
        // on the Main thread, a SECOND re-derivation of a fact this function owns, and a TORN
        // PAIR-READ whose two halves could land either side of a disk change.
        //
        // Now it is decided from the SAME snapshot the route is derived from. A completed destroy
        // proved image-bearing absence with its OWN required dirSync and retired both markers only
        // after that proof — evidence strictly stronger than the doubt any producer raised — so it,
        // and only it, may lower the hold.
        val hold =
            if (supersedeCompletedDestroy &&
                destroySupersedesDurabilityHold(
                    vaultProvenAbsent = residence.mayRouteToOnboarding,
                    serverDeleteConfirmed = confirmed,
                )
            ) {
                durabilityHold.value = false
                false
            } else {
                durabilityHold.value
            }
        deriveBootDecision(
            serverDeleteConfirmed = confirmed,
            imagePresent = residence is Residence.Present,
            durabilityHold = hold,
            vaultProvenAbsent = residence.mayRouteToOnboarding,
            isLegacyImage = { isLegacyImage() },
        )
    }

    /**
     * The image's [Residence] — the tri-state read that [hasVault] and [vaultProvenAbsent] encode
     * as two booleans a caller has to pair correctly.
     */
    internal fun vaultResidence(): Residence = Residence.classify(::hasVault, ::vaultProvenAbsent)

    /**
     * PROCESS-scoped reconciliation state.
     *
     * [bootReconciled] gates the fresh-install presentation: no route may be derived from disk until
     * boot reconciliation has finished, because its mutators CHANGE what disk says.
     *
     * ## [durabilityHold] — ONE OWNER, THREE PRODUCERS (0.9.2 Unit W-B)
     *
     * **It means exactly one thing: SOME DESTRUCTIVE MUTATION OF LOCAL STATE DID NOT PROVE DURABLE.
     * Full stop.** It carries forward the one fact a later stat cannot recover — files were unlinked
     * but a journal replay could bring them back — and withholds the fresh-install presentation for
     * the rest of this process.
     *
     * Three producers publish into this ONE field:
     *  1. [VaultImageStore.sweepOrphanedResidue] — the cold-start orphan sweep (W-A).
     *  2. [VaultImageStore.completeInterruptedBurn] / [VaultImageStore.reconcileOrphanedBurnMarkers] —
     *     the boot reconcilers (W-B).
     *  3. **[VaultImageStore.burnObliterate] — the duress wipe itself**, which runs at RUNTIME rather
     *     than at boot. This is the producer whose absence was the round-6 HIGH: the hold covered the
     *     boot sweep but not the burn's own obliterate, so a burn whose unlinks landed while its
     *     `dirSync` failed left a directory that STATS CLEAN — and the next boot presented ONBOARDING,
     *     a fresh install over a wipe that was never proven durable and that a journal replay can
     *     bring back. Closed STRUCTURALLY: same field, same meaning, one more producer.
     *
     * **ROUTING CARES ONLY THAT IT IS RAISED, NEVER WHICH PRODUCER RAISED IT.** There is deliberately
     * no discriminator, and adding one is not a fix. **If any consumer ever needs to know WHICH
     * mutation failed, that is the signal this single-field design has broken down — surface it as a
     * FINDING rather than working around it by widening the field.**
     *
     * PROCESS-scoped, not composition-scoped, and deliberately so: composition state resets on an
     * Activity recreation, and a rotation that cleared this hold would restore exactly the
     * fresh-install-over-unproven-absence presentation it exists to prevent.
     */
    val bootReconciled = MutableStateFlow(false)
    val durabilityHold = MutableStateFlow(false)

    /**
     * Apply-once carrier for the duress wipe's outcome. PROCESS-scoped for the same reason the hold
     * is: the wipe outlives the composition that started it, so an Activity recreation mid-wipe must
     * neither lose the outcome nor apply it twice.
     */
    internal val burnCompletion = BurnCompletionCoordinator()

    /**
     * Raise the [durabilityHold] — the single entry point for every producer.
     *
     * Monotonic within a process: a raised hold is never lowered by another producer's success, only
     * by evidence STRICTLY STRONGER than the doubt that raised it (a completed destroy's own proven
     * absence + `dirSync`, via [destroySupersedesDurabilityHold]). A producer that lowered it on its
     * own success would let a clean sweep erase a failed burn's doubt.
     */
    internal fun raiseDurabilityHold() {
        durabilityHold.value = true
    }

    /**
     * The DURESS wipe (0.9.2 Unit W-B) — producer 3 of the [durabilityHold].
     *
     * Fail-closed by construction: the hold is raised BEFORE the first destructive mutation is
     * attempted, and lowered only once the wipe has PROVEN itself durable. Raising it afterwards on
     * failure would lose the crash window — a process death mid-obliterate would leave no hold at all,
     * and the next boot would present a fresh install over an unproven wipe.
     *
     * Rethrows whatever [VaultImageStore.burnObliterate] throws: the caller decides presentation, but
     * the hold it leaves behind is what makes a failed burn safe regardless of what the caller does.
     */
    fun burnVault() = runBurnWipe(
        raiseHold = { raiseDurabilityHold() },
        obliterate = {
            imageStore.burnObliterate()
            // Keystore/prefs teardown is INSIDE the guarded region, not after it: an orphaned alias
            // is a surviving artifact, and a wipe with a surviving artifact is not a wipe. It runs
            // AFTER the image so a failure here cannot strand a recoverable vault — the image is
            // already proven gone by the time this can fail.
            if (!wipeBiometricMaterial()) throw VaultImageException.DestroyFailed()
            // THE DEVICE KEY IS AN ORACLE (gate finding, first execution). It is created LAZILY by
            // the first `wrapDek`, so a device that never made a vault does not have the alias —
            // leaving it behind proves one existed. Enumerated rather than fixed one-off: the app
            // creates three alias families, and this is the only other one that is
            // "exists only if the feature was used". `_androidx_security_master_key_` is created at
            // STARTUP by EncryptedSharedPreferences, so a fresh install has it too and wiping it
            // would break prefs — deliberately NOT touched.
            if (!deviceKeyCipher.deleteKeyMaterial()) throw VaultImageException.DestroyFailed()
            // THE "EXISTS ONLY IF THE FEATURE WAS USED" CLASS, ENUMERATED (round-1 review, Codex).
            // Fixing only the artifact a reviewer happened to name is the instance-fix this unit has
            // produced repeatedly; the class-fix is the default posture now. Every app-local writer
            // whose output a never-used device does NOT have:
            //   - BootDiagnostics: writes into filesDir on the FIRST record(), i.e. on first boot
            //     reconciliation of a real vault. A fresh install has no such file.
            //   - plaintext caches: populated only by a live session's attachment/QR paths.
            // The vault image, DEK, temps and markers are already covered by obliterateLocked().
            // NOT wiped and deliberately so: `_androidx_security_master_key_` and the prefs file
            // EncryptedSharedPreferences creates at STARTUP — a fresh install has both, so removing
            // them would CREATE a difference rather than erase one.
            // PROVEN, not best-effort (round-2 review, BLOCKING): `clear()` swallowed its own
            // failures and returned nothing, so the hold was lowered over a surviving log.
            if (!bootDiagnostics.clearProven()) throw VaultImageException.DestroyFailed()
            if (!runCatching { clearCacheDir(app.cacheDir) }.getOrDefault(false)) {
                throw VaultImageException.DestroyFailed()
            }
        },
        lowerHold = { durabilityHold.value = false },
    )

    private val bootReconcileStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Start boot reconciliation ONCE PER PROCESS; later callers no-op and observe [bootReconciled]. */
    fun startBootReconcile() {
        runBootReconcile(
            scope = scope,
            claim = { bootReconcileStarted.compareAndSet(false, true) },
            // ALL THREE boot mutators, ordered but ORDER-INDEPENDENT BY PROOF: their trigger
            // predicates are pairwise exclusive over the enumerated state space, asserted in
            // `BurnReconcilerTriggersTest`. That is a proof rather than the reasoning "they can't
            // both fire" — if a future change widens a trigger, the test fails loudly instead of the
            // ordering silently starting to matter.
            //
            // Each returns whether IT proved its own mutation durable; the results fold into the ONE
            // durability verdict below. A reconciler that mutated without proving durability raises
            // the hold exactly as a non-durable sweep does — one owner, one meaning.
            sweep = {
                val burnCompleted = imageStore.completeInterruptedBurn()
                val markersCleared = imageStore.reconcileOrphanedBurnMarkers()
                val sweepResult = imageStore.sweepOrphanedResidue()
                // Both reconcilers are best-effort and never throw: `false` means either "did not
                // fire" or "fired and could not prove itself durable", and those must not be
                // conflated. Re-derive the distinction from disk: if either reconciler's precondition
                // still holds after it ran, it mutated (or tried to) without landing — fail closed.
                // FOLD THE TRI-STATE (round-1 review, both lenses). The previous re-derivation
                // inspected only reconcilers that returned TRUE, so it structurally could not see the
                // ambiguous FALSE it claimed to resolve: a reconciler that unlinked and then failed
                // its dirSync reported `false`, the disk then stat'd clean, and the hold published
                // FALSE over a wipe that a journal replay can undo. Each reconciler now reports its
                // own durability, and any MUTATED_NOT_DURABLE raises the hold.
                val reconcileUnproven =
                    burnCompleted == ReconcileResult.MUTATED_NOT_DURABLE ||
                        markersCleared == ReconcileResult.MUTATED_NOT_DURABLE
                if (reconcileUnproven) ResidueSweepResult.SWEPT_NOT_DURABLE else sweepResult
            },
            publish = { hold ->
                durabilityHold.value = hold
                bootReconciled.value = true
            },
            afterPublish = {
                // Non-routing hygiene AFTER the gate opens — a slow cache clear must not hold splash.
                // No local runCatching: runBootReconcile contains faults here by contract.
                retryPlaintextCacheClearIfNoVault()
            },
        )
    }

    /**
     * Retry the plaintext-cache clear on a cold start. Cheap (a directory list), silent, self-healing.
     *
     * TRISTATE GATE: this DELETES on "no vault", so the gate must be a PROVEN absence
     * ([VaultImageStore.primaryImageProvenAbsent]) and not the `File.exists()`-backed routing signal —
     * a stat/I/O fault read as "absent" would clear the cache out from under a LIVE vault. The fail
     * direction is the safe one (over-clearing an OS-evictable cache at cold start), but the caller of
     * a destructive operation must not use the looser test.
     */
    fun retryPlaintextCacheClearIfNoVault(): Boolean {
        if (!imageStore.primaryImageProvenAbsent()) return false
        return runCatching { clearCacheDir(app.cacheDir) }.getOrDefault(false)
    }

    /**
     * Routing signal (0.9.2): a present image is the PRIOR (v2 / 0.9.1) format, which the burn-slot
     * reservation makes unsafe to unlock — route to fresh onboarding instead of the lock screen. A
     * cheap Argon2id-free peek (see [VaultImageStore.isLegacyImage]); call off-main. Safety does NOT
     * depend on this routing: [VaultImageStore.open] throws [VaultImageException.LegacyImage] before any
     * slot is interpreted, so a v2 image can never be misread as a burn wipe even if it reaches unlock.
     */
    fun isLegacyImage(): Boolean = imageStore.isLegacyImage()

    /**
     * Routing truth OVERRIDING [hasVault]: the SERVER account is CONFIRMED gone and the local
     * vault destroy is owed ([VaultImageStore.serverDeleteConfirmed]). The only valid route is
     * "finish the deletion" (retry [destroyVaultForAccountDeletion]) — never the unlock gate. This
     * is the ONLY authorisation for the auto-destroy route: a mere delete-INTENT (server outcome
     * unknown) does NOT set it (round 13 — the round-12 conflation was the P1).
     */
    fun serverDeleteConfirmed(): Boolean = imageStore.serverDeleteConfirmed()

    /**
     * A delete was INITIATED but the server delete is not confirmed (a crash/failure mid-delete).
     * The vault is still valid and the account may still exist, so boot routes to normal unlock and
     * clears this stale intent — it NEVER authorises destruction. See
     * [VaultImageStore.deleteIntentPending].
     */
    /**
     * SUSPEND, and it moves itself to IO (0.9.2 Unit W-B, item #5) — the same discipline as
     * [deriveBootDecisionFromDisk]. This was a plain function taking `imageLock` and stat'ing disk,
     * and its only caller invoked it bare from a composition `LaunchedEffect` on the Main thread.
     * The dispatcher move belongs INSIDE, where no caller can get it wrong; a requirement stated in
     * a comment is a requirement that will eventually be violated by one call site.
     */
    suspend fun vaultDeleteIntentPending(): Boolean =
        withContext(Dispatchers.IO) { imageStore.deleteIntentPending() }

    /** Persist the delete INTENT durably (throws if not durable; caller fails closed). */
    fun markVaultDeleteIntent() = imageStore.markDeleteIntent()

    /** Persist SERVER-DELETE-CONFIRMED durably — the auto-destroy authorisation. */
    fun markServerDeleteConfirmed() = imageStore.markServerDeleteConfirmed()

    /** Durable auth-protection signal: the delete-intent marker is present (round 16, R15-P2). */
    fun hasVaultDeleteIntentMarker() = imageStore.hasDeleteIntentMarker()

    // @Volatile so the transport apply-loop (running on Dispatchers.Default) and
    // the construction thread publish/read the current client consistently.
    @Volatile
    private var httpClient =
        CertificatePinning.buildClient(torEnabled = deviceSettings.torEnabled)

    private val transportInputs: StateFlow<TransportResolver.Inputs> =
        deviceSettings.transportInputs
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                deviceSettings.transportInputsSnapshot,
            )

    val transportResolver = TransportResolver(
        relayI2pDest = BuildConfig.RELAY_I2P_DEST,
        i2pProxyHost = BuildConfig.I2P_PROXY_HOST,
        inputs = transportInputs,
        isRouterInstalled = { I2pIntegration.isOfficialRouterInstalled(app) },
        isOrbotInstalled = { TorIntegration.isOrbotInstalled(app) },
        prober = HttpConnectI2pProber(),
        scope = scope,
    )

    /** On-device, adb-free connection diagnostics (Settings → Diagnostics). */
    val bootDiagnostics = BootDiagnostics(app)

    /**
     * The single session-scoped half of the graph — nullable and built ON UNLOCK
     * over the vault, not eagerly. Null while locked; a live [SessionContainer]
     * once [publishSession] hands a resolved [VaultOpen] to [UnlockController].
     */
    private val _session = MutableStateFlow<SessionContainer?>(null)
    val session: StateFlow<SessionContainer?> = _session.asStateFlow()

    private val lemonDropVeilController = LemonDropVeilController(
        scope = scope,
        isUnlocked = { _session.value != null },
        probe = { qrId ->
            _session.value?.lemonDropRedeemer?.probe(qrId)
                ?: LemonDropRedeemer.ProbeResult.Advocacy(LemonDropScanOutcome.UNKNOWN)
        },
    )

    val lemonDropVeil: MutableStateFlow<LemonDropVeil?> get() = lemonDropVeilController.veil

    /** Handle a scanned `/d/{id}` — see [LemonDropVeilController.onScan]. */
    fun onLemonDropLink(qrId: String) = lemonDropVeilController.onScan(qrId)

    /** Dismiss the veil and invalidate any in-flight/queued scan. */
    fun dismissLemonDropVeil() = lemonDropVeilController.dismiss()

    /** Drop a plaintext-bearing [LemonDropVeil.Delivered] when the Activity stops. */
    fun clearDeliveredLemonDropVeil() = lemonDropVeilController.clearDelivered()

    /**
     * The session-per-unlock lifecycle. Builds a fresh vault-backed [SessionContainer]
     * over the CURRENT transport from a resolved [VaultOpen], and tears it down (with a
     * final vault reseal via `runtime.close`) on lock. See [UnlockController].
     */
    val unlockController = UnlockController<SessionContainer>(
        newSessionScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
        // The vault path always builds via unlock(prepared) with a resolved VaultOpen; a
        // no-arg unlock has no VaultOpen to consume and is unused on this install.
        buildSession = { error("vault install builds sessions via unlock(prepared)") },
        publish = { published ->
            synchronized(transportLock) { _session.value = published }
            if (published == null) lemonDropVeilController.onLocked()
        },
        // Teardown: stop the coordinator THEN close the runtime (final reseal + state
        // wipe), under transportLock. The imageStore itself stays open (device half).
        // runtime.close() (the reseal + key-material wipe) runs in a finally so a
        // throw from coordinator.stop() can NEVER skip the wipe — otherwise a lock
        // would leave the slot key + decrypted plaintext resident in the heap.
        stopSession = {
            synchronized(transportLock) {
                try {
                    it.coordinator.stop()
                } finally {
                    it.runtime.close()
                }
            }
        },
        afterPublish = ::onSessionPublished,
    )

    /**
     * D3 idle auto-lock. Locks the vault through the SAME [unlockController] teardown after the
     * user's device-level timeout once the app is backgrounded — it only LOCKS (reseal + teardown),
     * never DELETES, so it adds no writer to the vault-delete / auth state. Registered on the
     * process lifecycle at construction (on the main thread, in Application.onCreate).
     */
    val vaultLockManager = VaultLockManager(
        scope = scope,
        timeoutSeconds = { deviceSettings.autoLockTimeoutSeconds },
        sessionLive = { _session.value != null },
        terminalWipe = { unlockController.isTerminalWipe() },
        lock = { unlockController.lock() },
        // Uninterrupted-sequence guard (0.9.2 triple-entry, spec §3): backgrounding the app breaks any
        // in-progress triple-entry ritual. Reset UNCONDITIONALLY on every onStop (independent of the
        // auto-lock decision, which is session-gated — the ritual runs at the LOCK screen with no live
        // session). Process death clears the RAM candidate on its own; a lock cycle cannot interrupt a
        // ritual because the ritual only runs while already at the lock screen.
        resetRitual = { unlockRouter.resetCandidate() },
    ).also { it.register(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle) }

    // ── Vault unlock / create orchestration (all off-main; caller drives the UI) ──

    /**
     * Create a fresh vault sealing an EMPTY keystore under [passphrase], then PUBLISH its session
     * (onboarding = first unlock) in the SAME off-main block — so a mid-work coroutine cancellation
     * can never discard the freshly created [VaultOpen] unwiped: [publishSession] consumes-or-wipes
     * it before this block returns, and the session it builds lives on the process scope, not the
     * Activity. Returns true once the session is published. CPU-heavy (Argon2id×SLOT_COUNT+1). Wipes
     * the orphaned legacy prefs at creation (the zero-users clean-break decision). Propagates
     * [com.zitrone.app.crypto.vault.VaultImageException.NotDurable] so the caller can surface a
     * retry (or re-derive [hasVault] and route to unlock) — creation NEVER bricks.
     */
    suspend fun createVaultAndPublish(passphrase: String): Boolean = withContext(Dispatchers.Default) {
        // A prior-format (v2 / 0.9.1) image is RETIRED here, on the deliberate onboarding action, so a
        // fresh v3 vault can be created (create() requires no existing image). retireLegacyImage()
        // re-proves the image is v2 and refuses to touch a valid current-version vault, so this is a
        // no-op unless an actual legacy image is present. Not silent: it happens only on create.
        if (imageStore.isLegacyImage()) imageStore.retireLegacyImage()
        val initial = VaultStateCodec.encode(VaultState.empty())
        val open = try {
            imageStore.create(passphrase, initial)
        } finally {
            // The genesis plaintext held nothing but empty holders, but zero it anyway —
            // create() does not consume its initialPayload.
            wipe(initial)
        }
        // `open` now holds a LIVE vault key + genesis payload. publishSession consumes them on an
        // accepted build and wipes them on a refused one; anything BEFORE that hand-off (the
        // best-effort legacy cleanup, a cancellation) must not abandon them unwiped.
        var handedOff = false
        try {
            // ZERO-USERS CLEAN BREAK (maintainer decision 2026-07-23): a pre-0.9.1 install
            // upgrading routes to vault setup, and creating the vault WIPES the orphaned legacy
            // signal/auth/contacts prefs — one-time hygiene, no data anyone owns (no accounts /
            // users exist). PREFS_SETTINGS (device settings + the biometric wrap) is deliberately
            // kept. Best-effort: a legacy-prefs error must NOT brick a fresh vault, so it is caught
            // and ignored rather than thrown.
            runCatching { wipeLegacyPrefs() }
            publishSession(open).also { handedOff = true }
        } finally {
            // Wipe only if publishSession never returned (a throw/cancellation before the hand-off):
            // once it returns it has already consumed-or-wiped the arrays, and re-wiping a key we
            // DID hand off would corrupt the running session.
            if (!handedOff) {
                wipe(open.vaultKey)
                wipe(open.payloadPlaintext)
            }
        }
    }

    /**
     * The 0.9.2 fused passphrase entry point (router). Off-main. In ONE block: run the triple-entry
     * gate to decide `create`, then [com.zitrone.app.crypto.vault.VaultImageStore.attemptUnlockOrAdd]
     * (identical heavy crypto every outcome — the plausible-deniability + duress timing contract), then
     * map the outcome and manage the router's RAM state:
     *  - a slot MATCH publishes the session ([UnlockOrAdd.Unlocked]) — discards the ritual, clears backoff;
     *  - a BURN slot match ([UnlockOrAdd.Burn]) — discards the ritual, leaves backoff untouched (not a
     *    wrong password); the caller performs the duress wipe;
     *  - a no-match CREATE ([UnlockOrAdd.Created]) publishes the new vault — discards the ritual, clears backoff;
     *  - a [UnlockOrAdd.Rejected] (no match, or a fail-closed create over a pending delete) KEEPS the
     *    triple-entry streak and advances the backoff — indistinguishable from a wrong passphrase.
     *
     * The `create` decision is computed on EVERY attempt so its SHA-256 + constant-time compare is
     * constant work (never a distinguisher). `genesis` (an empty [VaultState]) is encoded per attempt and
     * WIPED in `finally`; the store copies it only on a create and never wipes the caller's copy. The
     * materialized [VaultOpen] is consumed-or-wiped by [publishSession] synchronously before this block
     * returns, so a cancellation can never strand it. Never logs anything credential-shaped.
     */
    suspend fun attemptPassphrase(passphrase: String): PassphraseOutcome {
        // PROCESS-scoped single-flight (see [unlockInFlight]): refuse a concurrent attempt BEFORE any
        // decideCreate, so a rotation that starts a second attempt while a cancelled first one's
        // uninterruptible store is still finishing can never advance the triple-entry streak. A busy
        // reject is uniform (like a wrong password) and touches neither the streak nor the backoff.
        // The composition-local `unlocking` guard already blocks concurrent submits WITHIN one Activity;
        // this closes only the cross-recreation race the two round-5 reviewers converged on.
        if (!tryBeginUnlock()) return PassphraseOutcome.Rejected
        // The triple-entry candidate is advanced inside decideCreate (a side effect that persists even
        // when the attempt is later cancelled). ANY cancellation of this attempt must undo that advance —
        // an interrupted entry is never one of the 3 uninterrupted identical entries. The reset lives in
        // the OUTER catch, around the whole withContext, because withContext's prompt-cancellation
        // guarantee can DISCARD the block's already-computed Rejected result and throw CancellationException
        // at the boundary — after the `when` kept the streak — where no catch INSIDE the block (having
        // already returned) can ever see it. The inner catch only covers a CE thrown DURING the store call.
        // endUnlock() is in the OUTER finally: it runs AFTER the CE-reset catch, so the single-flight is
        // released only once this attempt's rollback (or commit) is complete — a next attempt that claims
        // the flight therefore always reads a settled streak.
        return try {
            withContext(Dispatchers.Default) {
                val create = unlockRouter.decideCreate(passphrase)
                val genesis = VaultStateCodec.encode(VaultState.empty())
                try {
                    val result = try {
                        imageStore.attemptUnlockOrAdd(passphrase, genesis, create)
                    } catch (c: CancellationException) {
                        // Re-throw untouched so (a) the Throwable catch below can't swallow it into Rejected
                        // and (b) the OUTER catch performs the single candidate reset for every CE path.
                        throw c
                    } catch (e: VaultImageException.LegacyImage) {
                        unlockRouter.resetCandidate()
                        return@withContext PassphraseOutcome.LegacyImage
                    } catch (e: VaultImageException.CorruptImage) {
                        unlockRouter.resetCandidate()
                        return@withContext PassphraseOutcome.ImageUnreadable
                    } catch (e: VaultImageException.MissingImage) {
                        unlockRouter.resetCandidate()
                        return@withContext PassphraseOutcome.ImageUnreadable
                    } catch (e: VaultImageException.NotDurable) {
                        // Create wrote but durability unconfirmed; the new vault IS in canonical, so a later
                        // single entry unlocks it via the match path. Spend the ritual, bump backoff, retry.
                        unlockRouter.resetCandidate()
                        unlockRouter.recordFailure()
                        return@withContext PassphraseOutcome.Retry
                    } catch (t: Throwable) {
                        // Any other throw (a self-verify IllegalState, a transient IO) → generic; never leak it.
                        unlockRouter.resetCandidate()
                        unlockRouter.recordFailure()
                        return@withContext PassphraseOutcome.Rejected
                    }
                    when (result) {
                        is UnlockOrAdd.Unlocked -> {
                            unlockRouter.resetCandidate()
                            if (publishSession(result.open)) {
                                unlockRouter.recordSuccess(); PassphraseOutcome.Unlocked
                            } else {
                                unlockRouter.recordFailure(); PassphraseOutcome.Rejected
                            }
                        }
                        is UnlockOrAdd.Created -> {
                            unlockRouter.resetCandidate()
                            if (publishSession(result.open)) {
                                unlockRouter.recordSuccess(); PassphraseOutcome.Created
                            } else {
                                unlockRouter.recordFailure(); PassphraseOutcome.Rejected
                            }
                        }
                        UnlockOrAdd.Burn -> {
                            unlockRouter.resetCandidate()
                            PassphraseOutcome.Burn
                        }
                        UnlockOrAdd.Rejected -> {
                            // KEEP the streak (do NOT reset) so a genuine ritual can complete; advance backoff.
                            unlockRouter.recordFailure()
                            PassphraseOutcome.Rejected
                        }
                    }
                } finally {
                    wipe(genesis)
                }
            }
        } catch (c: CancellationException) {
            // Deferred-boundary reset: undoes the decideCreate advance for a cancellation delivered at the
            // withContext boundary (block already returned; streak kept) OR re-thrown from the inner catch.
            unlockRouter.resetCandidate()
            throw c
        } finally {
            // Release the single-flight AFTER the CE-reset catch above, so no concurrent attempt can claim
            // the flight until this one's streak rollback/commit has settled.
            endUnlock()
        }
    }

    /**
     * Recover the vault key from [wrap] with an already-AUTHENTICATED [decryptCipher] (from a
     * successful CryptoObject BiometricPrompt), open the slot with it off-main, and PUBLISH the
     * session — the open+publish share one off-main block so cancellation can't strand the
     * [VaultOpen]. The recovered vault key is wiped in `finally` (unlockWithKey holds its own
     * independent copy — store contract :474-478). Returns whether a session was published (false
     * on an AEAD failure / no match / refused build).
     */
    suspend fun unlockWithBiometric(
        decryptCipher: javax.crypto.Cipher,
        wrap: com.zitrone.app.crypto.vault.BiometricWrappedKey,
    ): Boolean = withContext(Dispatchers.Default) {
        // The whole body — including openVaultKey's Cipher.doFinal — runs off-main so no crypto ever
        // executes on the caller (main) thread.
        val vaultKey = biometricCipher.openVaultKey(decryptCipher, wrap.blob) ?: return@withContext false
        try {
            val open = imageStore.unlockWithKey(vaultKey, wrap.slotIndex) ?: return@withContext false
            publishSession(open)
        } finally {
            wipe(vaultKey)
        }
    }

    /**
     * Enable biometric unlock over the LIVE [session] (spec §1): wrap a COPY of the running slot's
     * vault key — obtained via the narrow [SessionContainer.withVaultKey], wiped in its `finally` —
     * under the auth-gated biometric key with an already-AUTHENTICATED [encryptCipher], and persist
     * the constant-size `{ slotIndex, blob }` wrap. Returns true on success. Used by BOTH the
     * onboarding enable offer (post-publish) and the Settings toggle, so no live [VaultOpen] is ever
     * held across a recomposition.
     */
    fun enableBiometricFromSession(
        encryptCipher: javax.crypto.Cipher,
        session: SessionContainer,
        aliasId: String,
    ): Boolean {
        // A-BOUND SINGLE WRAP (OQ4, "one wrap, never repointed"): allow the write ONLY when no wrap
        // exists (first-enable-wins, OQ-A(i)) OR the existing wrap already names THIS session's slot
        // (same-vault re-enable). Any OTHER slot is refused fail-closed (write nothing, no repoint). The
        // slot-agnostic isEnabled() check at the entrypoint is the primary UX gate; the per-slot belt
        // below is enforced UNDER [biometricWriteLock] atomically with the save, so it also catches a
        // concurrent cross-slot enable (TOCTOU) — the later commit sees the earlier wrap and refuses.
        // The A-only restriction stays purely a write-path property; every enroll UI surface is
        // slot-agnostic so an A-session and a B-session render identically.
        return session.withVaultKey { key ->
            // Seal OUTSIDE the lock (crypto over the live key); commit UNDER it. The commit re-checks the
            // never-repoint belt AND that this enable's own alias still exists (a concurrent
            // disable/account-delete/GC may have reaped it), atomically with the save — so the persisted
            // wrap always references an existing sealing alias (INV-1) and two cross-slot first-enables
            // cannot both commit (the later one's belt now sees the earlier wrap and refuses).
            val blob = biometricCipher.sealVaultKey(encryptCipher, key)
            synchronized(biometricWriteLock) {
                if (!unlockRouter.biometricEnableAllowed(biometricStore.boundSlotIndex(), session.slotIndex)) {
                    return@synchronized false
                }
                if (!biometricCipher.keyExists(aliasId)) return@synchronized false // reaped mid-flight → abort
                biometricStore.save(
                    com.zitrone.app.crypto.vault.BiometricWrappedKey(session.slotIndex, aliasId, blob),
                )
                true
            }
        }
    }

    /**
     * Disable biometric unlock: drop the persisted wrap AND reap EVERY per-enable auth-gated Keystore
     * key (`deleteAllAliasesExcept(null)`), so no stale alias survives a disable. Idempotent.
     */
    fun disableBiometric() {
        synchronized(biometricWriteLock) {
            biometricStore.clear()
            biometricCipher.deleteAllAliasesExcept(null)
        }
    }

    /**
     * Reap stale biometric Keystore aliases (called once at cold-start container init, off-main):
     * delete every per-enable alias except the one the current wrap references. Bounds accumulation
     * from superseded/abandoned enables; best-effort (leftover aliases are harmless — unlock uses the
     * wrap's own alias). SAFE to run concurrently with an in-flight enable: under [biometricWriteLock]
     * it reads the live wrap's alias and deletes the others atomically, so a concurrent enable either
     * has its just-saved wrap's alias kept (it is `keep`) or aborts at its own `keyExists` re-check
     * under the same lock — it can never delete the alias the current wrap references (INV-1).
     */
    fun reapStaleBiometricAliases() {
        synchronized(biometricWriteLock) {
            biometricCipher.deleteAllAliasesExcept(biometricStore.boundAliasId())
        }
    }

    /**
     * The account-deletion primitive (NO REMANENCE). DESTROYS every on-disk + in-RAM trace of the
     * vault: [VaultImageStore.destroy] deletes `vault.bin` + `vault.dek` (+ tmp leftovers), wipes the
     * RAM DEK, and releases the single-instance registration; then the biometric wrap + its auth-gated
     * Keystore key are removed. Each step tolerates its OWN throw (a [CancellationException] still
     * propagates) so one failure can't strand the rest — the IMAGE destroy is the load-bearing one for
     * the deletion-permanence promise. Idempotent.
     *
     * Do NOT confuse with `runtime.close()` / `signalStore.wipe()`, which RESEAL the image (keeping the
     * account's crypto on disk) — those are a lock, not a deletion. This MUST run AFTER the session
     * teardown (runtime.close reseals the image); destroy() then deletes it, so NO resealed image
     * survives. After this call [hasVault] is false → the app routes to Onboarding (fresh-install state).
     *
     * The IMAGE destroy is the load-bearing no-remanence step and is NOT tolerated: [VaultImageStore.destroy]
     * verifies the unlink and THROWS [com.zitrone.app.crypto.vault.VaultImageException.DestroyFailed] if a
     * file survived, and that throw PROPAGATES so the caller does not claim a delete that did not take (it
     * surfaces a retry rather than routing to Onboarding-as-success). The biometric wrap/key removals are
     * best-effort hygiene (useless once the image is gone) and run FIRST, tolerated, so a Keystore hiccup
     * there cannot mask — or pre-empt — the image destroy's success/failure signal.
     */
    fun destroyVaultForAccountDeletion() {
        // Under the same lock as enable-commit, so a racing in-flight enable cannot re-persist a wrap
        // after this cleanup (it would abort on the keyExists check once these aliases are gone).
        wipeBiometricMaterial()
        // NOT tolerated: a DestroyFailed (a surviving file) MUST reach the caller as a NOT-deleted signal.
        imageStore.destroy()
    }

    /**
     * Remove every biometric wrap and Keystore alias — shared by the account-delete path and the
     * duress burn (0.9.2 Unit W-B), so the two can never drift into clearing different sets.
     *
     * Under [biometricWriteLock], the same lock as enable-commit, so a racing in-flight enable cannot
     * re-persist a wrap after this runs (it aborts on its `keyExists` check once the aliases are
     * gone).
     *
     * Returns true iff the cleanup completed. **The burn path CONSUMES that boolean** — an orphaned
     * Keystore alias is "something was here" residue, and post-burn ≡ fresh install is this feature's
     * purpose. The account-delete path keeps the historical best-effort semantics: there the
     * load-bearing step is the image destroy, and a Keystore already unhealthy must not strand it.
     */
    internal fun wipeBiometricMaterial(): Boolean {
        var ok = true
        tolerateCleanup {
            try {
                synchronized(biometricWriteLock) {
                    biometricStore.clear()
                    biometricCipher.deleteAllAliasesExcept(null)
                }
            } catch (t: Throwable) {
                ok = false
                throw t
            }
        }
        return ok
    }

    /**
     * Run one account-deletion cleanup step, tolerating its own non-cancellation throw so a single
     * failure (e.g. a Keystore already unhealthy) can't strand the remaining steps. A
     * [CancellationException] is rethrown BEFORE the broad catch so cooperative cancellation still
     * unwinds — the package-wide catch-ordering discipline.
     */
    private inline fun tolerateCleanup(step: () -> Unit) {
        try {
            step()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Tolerated: one cleanup's failure must not strand the others (the image destroy is the
            // load-bearing one; the biometric removals are best-effort hygiene).
        }
    }

    /** Reveal the passphrase lock screen while KEEPING a queued lemon-drop scan (see controller). */
    fun revealLockScreenKeepingLemonDropScan() =
        lemonDropVeilController.revealLockScreenKeepingScan()

    /**
     * Hand a resolved [vaultOpen] to the session build. On an accepted build the VaultSession
     * consumes its arrays; a REFUSED build (terminal wipe / already live) wipes them here so no
     * vault key or plaintext is abandoned, and a BUILD THROW is wiped by [UnlockController] +
     * SessionContainer's construction guard, then rethrown. Returns whether a session was actually
     * published (so the caller never reports success onto a null session). Marks onboarding complete
     * (first unlock = onboarding completion) only when a session was published.
     */
    fun publishSession(vaultOpen: VaultOpen): Boolean {
        var published = false
        try {
            unlockController.unlock(
                prepared = { sessionScope ->
                    buildVaultSession(sessionScope, vaultOpen).also { published = true }
                },
                onRefused = {
                    wipe(vaultOpen.vaultKey)
                    wipe(vaultOpen.payloadPlaintext)
                },
            )
        } finally {
            // Any live session ENDS/interrupts an in-progress triple-entry ritual — reset here so the
            // guard covers EVERY unlock path uniformly (passphrase, BIOMETRIC, onboarding create), not
            // just the passphrase path. In a `finally` keyed on `published` so it runs EVEN IF a
            // post-publish step (afterPublish / the settings write below) throws AFTER the session went
            // live: without this, a soft exception on the biometric path could leave a mid-ritual
            // candidate alive over a published session, to be completed by one lock-screen entry after a
            // later non-background re-lock. A refused (non-published) build must NOT reset — no session.
            if (published) unlockRouter.resetCandidate()
        }
        if (published) settingsRepository.setOnboardingDone(true)
        return published
    }

    private fun buildVaultSession(sessionScope: CoroutineScope, vaultOpen: VaultOpen): SessionContainer {
        val (client, apiBase, ws) = transportEndpoints(transportResolver.state.value)
        httpClient = client
        return SessionContainer(
            app = app,
            scope = sessionScope,
            bootDiagnostics = bootDiagnostics,
            settings = settingsRepository,
            httpClient = httpClient,
            apiBaseUrl = apiBase,
            wsUrl = ws,
            vaultOps = vaultOps,
            vaultOpen = vaultOpen,
            persist = imageStore::writeSealedPayload,
            persistDeleteIntent = imageStore::markDeleteIntent,
            persistServerDeleteConfirmed = imageStore::markServerDeleteConfirmed,
            intentMarkerPresent = imageStore::hasDeleteIntentMarker,
        )
    }

    /** Clear the orphaned legacy stores at vault creation (see [createVaultAndPublish]). */
    private fun wipeLegacyPrefs() {
        keyStoreManager.prefs(KeyStoreManager.PREFS_SIGNAL_STORE).edit().clear().apply()
        keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH).edit().clear().apply()
        keyStoreManager.prefs(KeyStoreManager.PREFS_CONTACTS).edit().clear().apply()
    }

    private fun onSessionPublished() {
        synchronized(transportLock) {
            applyTransportLocked(transportResolver.state.value)
        }
        lemonDropVeilController.onUnlocked()
    }

    private val transportLock = Any()

    init {
        transportResolver.start()
        scope.launch {
            transportResolver.state.collect(::applyTransport)
        }
        // Cold-start GC of superseded/abandoned per-enable biometric aliases (0.9.2 enable-atomicity),
        // off-main. Safe even if an enable races it: reapStaleBiometricAliases holds biometricWriteLock
        // and keeps the live wrap's alias, and the enable-commit re-checks keyExists under the same lock.
        scope.launch(Dispatchers.IO) { runCatching { reapStaleBiometricAliases() } }
    }

    private fun applyTransport(state: TransportState) =
        synchronized(transportLock) { applyTransportLocked(state) }

    private fun applyTransportLocked(state: TransportState) {
        if (state != transportResolver.state.value) return
        val (client, apiBase, ws) = transportEndpoints(state)
        httpClient = client
        val live = _session.value
        live?.apiClient?.updateTransport(httpClient, apiBase)
        live?.wsClient?.updateTransport(httpClient, ws)
        if (state == TransportState.TOR) TorIntegration.requestOrbotStart(app)
        if (live != null &&
            live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED
        ) {
            live.wsClient.disconnect()
            live.apiClient.accessToken?.let(live.wsClient::connect)
        }
    }

    companion object {
        // Self-hosters: point these at your deployment AND replace the
        // certificate pin in net/CertificatePinning.kt.
        // TODO(zitrone-cutover): live relay endpoint — repoint only at deploy cutover.
        const val API_BASE_URL = "https://relay.sublemonable.com"
        const val WS_URL = "wss://relay.sublemonable.com/ws"

        private val i2pApiBaseUrl = "http://${BuildConfig.RELAY_I2P_DEST}"
        private val i2pWsUrl = "ws://${BuildConfig.RELAY_I2P_DEST}/ws"

        internal fun transportEndpoints(state: TransportState): Triple<OkHttpClient, String, String> =
            when (state) {
                TransportState.I2P -> Triple(
                    CertificatePinning.buildI2pClient(
                        BuildConfig.I2P_PROXY_HOST,
                        BuildConfig.RELAY_I2P_DEST,
                    ),
                    i2pApiBaseUrl,
                    i2pWsUrl,
                )
                TransportState.TOR ->
                    Triple(CertificatePinning.buildClient(torEnabled = true), API_BASE_URL, WS_URL)
                else -> Triple(CertificatePinning.buildClient(torEnabled = false), API_BASE_URL, WS_URL)
            }
    }
}

/**
 * Session-scoped half of the object graph — the messaging objects that live only
 * while a slot is unlocked, VAULT-BACKED (PR-D2c). Built per unlock ([UnlockController])
 * from a resolved [VaultOpen], against the transport resolved at that moment. The object
 * set and construction order match the pre-vault build; only the backing store changed —
 * every facade is a behavioural twin over one shared [VaultRuntime], so the consumers
 * (SignalProtocolManager / ApiClient / ConversationRepository / the lemon-drop objects)
 * are UNCHANGED.
 *
 * Construction ORDER is load-bearing: runtime → signalStore → signalManager → apiClient →
 * wsClient → messageRepository → conversationRepository → lemon-drop redeemer/creator →
 * notificationScheduler → coordinator.
 */
class SessionContainer(
    app: Application,
    scope: CoroutineScope,
    bootDiagnostics: BootDiagnostics,
    settings: SettingsRepository,
    httpClient: OkHttpClient,
    apiBaseUrl: String,
    wsUrl: String,
    vaultOps: VaultSodiumOps,
    vaultOpen: VaultOpen,
    persist: (slotIndex: Int, sealedPayload: ByteArray) -> Unit,
    /** Two-phase account-deletion markers (round 13) — see [MessagingCoordinator]. */
    persistDeleteIntent: () -> Unit = {},
    persistServerDeleteConfirmed: () -> Unit = {},
    intentMarkerPresent: () -> Boolean = { false },
) {
    /** Which image slot this session unlocked — needed to persist a biometric re-wrap ([withVaultKey]). */
    val slotIndex: Int = vaultOpen.slotIndex

    /** The single mutation gate over this slot's keystore (see the [VaultRuntime] kdoc). */
    val runtime: VaultRuntime

    // The VaultSession that owns this slot's key + payload. Held ONLY so [withVaultKey] can hand a
    // wiped-in-finally COPY of the vault key to the biometric re-wrap (spec §1); not used otherwise.
    private val vaultSession: VaultSession

    // The concrete facade is kept for the atomic contact-delete's flush-free record removal;
    // consumers see the store-agnostic [ZitroneSignalStore] seam (D2a), unchanged over either store.
    private val vaultSignalStore: VaultSignalProtocolStore
    val signalStore: ZitroneSignalStore
    val signalManager: SignalProtocolManager
    val apiClient: ApiClient
    val wsClient: WsClient
    val messageRepository: MessageRepository
    val conversationRepository: ConversationRepository

    /**
     * Vault-scoped settings facade — HELD but NOT yet driving SettingsScreen (that switch is
     * D5). D2c keeps every vault-scoped setting reading legacy prefs on all paths to avoid a
     * split-brain; this reference just proves the facade slots in.
     */
    val vaultSettingsStore: VaultSettingsStore
    val lemonDropRedeemer: LemonDropRedeemer
    val lemonDropCreator: LemonDropCreator
    val notificationScheduler: NotificationScheduler
    val coordinator: MessagingCoordinator

    init {
        // DECODE a defensive COPY of the payload FIRST — before the [VaultSession] constructor
        // destructively consumes the VaultOpen's payload + key arrays (VaultSession.kt:54-59) — so
        // the two never race over the same buffers, and the copy is wiped in `finally`. A decode
        // failure (e.g. a downgrade over a newer state version) throws HERE, before any
        // VaultSession/runtime exists: the caller's onRefused wipes the still-intact VaultOpen and
        // UnlockController cancels the freshly created scope.
        val decoded: VaultState = run {
            val copy = vaultOpen.payloadPlaintext.copyOf()
            try {
                VaultStateCodec.decode(copy)
            } finally {
                wipe(copy)
            }
        }
        val session = VaultSession(
            scope = scope,
            ops = vaultOps,
            initialPayload = vaultOpen.payloadPlaintext,
            initialVaultKey = vaultOpen.vaultKey,
            slotIndex = vaultOpen.slotIndex,
            persist = persist,
        )
        vaultSession = session
        val rt = VaultRuntime(session, decoded)
        runtime = rt
        // From here the runtime holds this slot's live key + payload copies. Any throw while
        // building the facades / coordinator below would otherwise abandon a live VaultSession on
        // the heap with no reseal or wipe — so reseal + wipe it via runtime.close() (idempotent)
        // and rethrow; UnlockController.unlock cancels the scope on that rethrow.
        try {
            vaultSignalStore = VaultSignalProtocolStore(rt)
            signalStore = vaultSignalStore
            signalManager = SignalProtocolManager(signalStore)
            apiClient = ApiClient(apiBaseUrl, httpClient, VaultAuthStore(rt))
            wsClient = WsClient(wsUrl, httpClient, scope) { line ->
                Log.w("ZitroneBoot", line)
                bootDiagnostics.record(line)
            }
            messageRepository = MessageRepository(scope)
            conversationRepository = ConversationRepository(VaultRosterStore(rt))
            vaultSettingsStore = VaultSettingsStore(rt)
            lemonDropRedeemer = LemonDropRedeemer(
                api = apiClient,
                signalStore = signalStore,
                conversations = conversationRepository,
                sodium = LemonDropSodiumOps(SodiumAndroid()),
                // Flush-before-handoff for the open path: the consumed prekey must reach disk
                // before the burn hands the relay its shred order (deliverDurablyThenBurn).
                flushDurable = rt::flushBeforeAck,
            )
            lemonDropCreator = LemonDropCreator(
                api = apiClient,
                signalStore = signalStore,
                conversations = conversationRepository,
                messages = messageRepository,
                sodium = LemonDropSodiumOps(SodiumAndroid()),
            )
            notificationScheduler = NotificationScheduler(
                scope = scope,
                fire = { MessagingNotifications.showNewMessage(app) },
                isEnabled = { settings.settings.value.unreadReminderEnabled },
                hasUnread = { conversationId ->
                    messageRepository.conversationMessages(conversationId)
                        .any { !it.isMine && it.state == MessageState.DELIVERED }
                },
                clock = { android.os.SystemClock.elapsedRealtime() },
            )
            coordinator = MessagingCoordinator(
                appContext = app,
                scope = scope,
                signal = signalManager,
                api = apiClient,
                ws = wsClient,
                messages = messageRepository,
                conversations = conversationRepository,
                settings = settings,
                diagnostics = bootDiagnostics,
                notificationScheduler = notificationScheduler,
                vaultContactDelete = ::deleteContactAtomically,
                // Flush-before-ack barrier (D2c, absorbs D4): the coordinator reseals the receiving
                // ratchet durably before acking each inbound delivery. rt is the live runtime.
                flushBeforeAck = rt::flushBeforeAck,
                // Two-phase deletion markers (round 13): intent before the server delete, confirmed
                // only after the server confirms gone; clear-intent abandons a definite failure.
                persistDeleteIntent = persistDeleteIntent,
                persistServerDeleteConfirmed = persistServerDeleteConfirmed,
                intentMarkerPresent = intentMarkerPresent,
            )
        } catch (t: Throwable) {
            runCatching { rt.close() }
            throw t
        }
    }

    /**
     * Hand a wiped-in-finally COPY of the live vault key to [block] (delegates to
     * [VaultSession.withVaultKey]). The ONLY use is biometric enable over a live session (spec §1)
     * — dual-wrapping the vault key without re-deriving it from the passphrase.
     */
    fun <T> withVaultKey(block: (ByteArray) -> T): T = vaultSession.withVaultKey(block)

    /**
     * Vault contact-delete atomicity (VaultSignalProtocolStore :222-231): the roster entry +
     * tombstone + crypto-record removal seal in ONE [VaultRuntime.mutate] + ONE
     * [VaultRuntime.flushBeforeAck], run INSIDE [ConversationRepository.deleteContactDurably] so the
     * whole operation holds that repo's monitor — the single serialization point that keeps a
     * concurrent roster write from resurrecting or losing an entry. Returns whether the durable
     * flush confirmed; the removal is applied in memory + live state regardless (never rolled back —
     * the crypto cannot be un-removed), so a false return means "unconfirmed durable", not "kept".
     */
    private suspend fun deleteContactAtomically(
        conversationId: String,
        contactId: String,
        at: Long,
    ): ContactDeleteOutcome {
        // Set from INSIDE the mutate block, AFTER the removal has touched live state but BEFORE
        // encode can throw. That placement is load-bearing for the outcome mapping: a closed-runtime
        // mutate throws its `check(!closed)` BEFORE the block runs, so this stays false → NOT_APPLIED
        // (the delete did not take). But a VaultCapacityException thrown by mutate's ENCODE happens
        // AFTER the block already mutated live state, so this is already true → APPLIED_UNCONFIRMED
        // (the crypto IS gone from the runtime; it persists on the next flush that fits), NOT a false
        // NOT_APPLIED. Captured across the seal lambda, which runs synchronously.
        var mutateApplied = false
        return conversationRepository.deleteContactDurably(conversationId, contactId, at) { rosterJson, tombstonesJson ->
            // BOTH mutate and flush are contained: a teardown race (forced logout /
            // revocation runs runtime.close() while this delete is mid-seal) makes
            // mutate throw IllegalStateException("closed") — synchronous, so
            // cancellation can't preempt it. Uncaught, that would crash the
            // confined worker (no CoroutineExceptionHandler) AND leave a half-delete
            // (burnAll already ran; the RAM/tombstone reconcile in the caller would
            // be skipped). Caught, it degrades to a false — and [mutateApplied] tells
            // a lost delete from an unconfirmed one, so the OUTCOME (not just a bool)
            // is returned to the repository: it keeps its RAM entry + tombstone on
            // NOT_APPLIED (the contact is still present). The removal, once applied,
            // is never rolled back.
            val durable = sealDurableOrFalse {
                runtime.mutate { state ->
                    vaultSignalStore.removeContactCryptoRecords(state, contactId)
                    rosterJson?.let { state.rosterJson = it }
                    state.tombstonesJson = tombstonesJson
                    // Mark applied HERE — the removal is now in live state. A capacity-during-encode
                    // throw (below, still inside mutate) then reports APPLIED_UNCONFIRMED, not
                    // NOT_APPLIED; a closed-runtime throw never reaches this line.
                    mutateApplied = true
                }
                runtime.flushBeforeAck()
            }
            contactDeleteOutcome(durable, mutateApplied)
        }
    }
}

/**
 * Runs a vault durability [seal] (a mutate + [VaultRuntime.flushBeforeAck]) and maps its outcome to
 * the [ConversationRepository.deleteContactDurably] contract: `true` when it committed durably;
 * `false` on a NON-cancellation failure ("unconfirmed durable" — the removal is NEVER rolled back,
 * so a false means "not confirmed", not "kept"); and a RETHROWN [CancellationException] so a scope
 * teardown mid-delete (forced logout / revocation running runtime.close()) UNWINDS cooperatively
 * instead of being folded into a false.
 *
 * Extracted top-level (mirroring [flushThenAck]) so the catch-ORDERING — rethrow the cancellation
 * BEFORE the broad `catch (Throwable) -> false` — is host-testable without a live SessionContainer.
 * That ordering is the whole point: were the order reversed, a real teardown cancellation would be
 * swallowed as a false. NOTE a full vault ([VaultCapacityException]) and a closed runtime both throw
 * [IllegalStateException], which lands in the Throwable arm as an honest `false`; only cooperative
 * cancellation escapes.
 */
internal fun sealDurableOrFalse(seal: () -> Unit): Boolean =
    try {
        seal()
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        false
    }


/**
 * The boot-reconciliation OWNER, extracted so its lifecycle contract is testable on the host JVM.
 * Four properties, each of which is a real failure mode:
 *
 *  1. **Once only.** [claim] is the CAS; a second call does nothing.
 *  2. **Publication ordering.** [publish] runs before any consumer is released — consumers await the
 *     published verdict instead of reading a field's default.
 *  3. **Fail-closed default.** The verdict starts at [ResidueSweepResult.SWEPT_NOT_DURABLE], so a run
 *     that dies before proving the disk durably clean releases waiters WITHHOLDING the fresh-install
 *     presentation. A permissive default would make the race invisible and wrong exactly when it
 *     matters.
 *  4. **Cancellation cannot strand the claim.** [publish] is in a `finally`, so a claimant cancelled
 *     after claiming and before publishing still releases every waiter. Without this the CAS stays
 *     true with no other writer and every later consumer blocks forever.
 *
 * [scope] and [ioDispatcher] are injected precisely so a test can drive cancellation deterministically
 * in virtual time. Production passes the process-scoped [AppContainer.scope] explicitly and does NOT
 * pass [ioDispatcher] at all — it relies on the `Dispatchers.IO` default in the signature below.
 * (Round-4 review, Kimi: this line previously said production "passes … `Dispatchers.IO`", which
 * reads as an explicit argument and would send a reader looking for a call site that does not exist.)
 */
internal fun runBootReconcile(
    scope: CoroutineScope,
    claim: () -> Boolean,
    sweep: () -> ResidueSweepResult,
    publish: (hold: Boolean) -> Unit,
    afterPublish: () -> Unit = {},
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    if (!claim()) return
    scope.launch {
        // FAIL-CLOSED default: only a completed, proven-durable sweep may lower this.
        var result = ResidueSweepResult.SWEPT_NOT_DURABLE
        try {
            withContext(ioDispatcher) {
                // NOT `runCatching` — that swallows CancellationException too, turning a cancelled
                // boot into a "successful" one. A cancellation must propagate to the `finally`, which
                // publishes the fail-closed default; only a genuine fault degrades and continues.
                result = try {
                    sweep()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    ResidueSweepResult.SWEPT_NOT_DURABLE
                }
            }
        } finally {
            // On EVERY exit — normal, throw, or cancellation. Non-suspending, so it still runs while
            // the coroutine is being cancelled.
            publish(result == ResidueSweepResult.SWEPT_NOT_DURABLE)
        }
        // CONTAINED (round-3 review, Gemini). This runs AFTER the verdict is published, so it can
        // never affect routing — but an uncaught throw here propagates out of the launch and, on
        // Android, reaches the default handler and takes the process down. Production deliberately
        // passes a BARE lambda (`startBootReconcile`, ~line 285) and relies on containment HERE: a
        // local runCatching at the call site would protect only today's caller, so the guarantee
        // belongs in the wrapper, where it covers every future one. A fault in post-publication
        // hygiene must not be able to kill the app.
        // (Follow-up review, Codex + Grok independently: this line said "Production's lambda wraps
        // itself" — the SAME stale fact `bdde066` corrected in two other places and missed in this
        // third one. See failures.md: enumerate every instance before committing a correction.)
        withContext(ioDispatcher) { runCatching { afterPublish() } }
    }
}

/**
 * Derive a boot decision from disk in ONE place. All three consumers (the Splash decision, the
 * post-boot re-derive, and the session collector) call this rather than each assembling the five
 * `bootRoute` inputs themselves.
 *
 * Round-1 review (Gemini): the derivation — including the ~1 MiB `isLegacyImage()` decrypt and its
 * skip conditions — was copy-pasted across all three call sites. Three copies of a safety derivation
 * drift silently: change one and the others keep the old rule, with no test able to catch the
 * divergence. One owner, one derivation. This is also why the legacy probe's cost and its
 * "only when it can matter" guard live here rather than being restated three times.
 *
 * MUST be called off the main thread — `isLegacyImage()` reads and decrypts the outer layer.
 */
internal fun deriveBootDecision(
    serverDeleteConfirmed: Boolean,
    imagePresent: Boolean,
    durabilityHold: Boolean,
    vaultProvenAbsent: Boolean,
    isLegacyImage: () -> Boolean,
): BootDecision {
    // Computed only when it can matter: never over a confirmed delete (that state is owned elsewhere)
    // and never with no image to inspect.
    val legacy = if (imagePresent && !serverDeleteConfirmed) {
        runCatching { isLegacyImage() }.getOrDefault(false)
    } else {
        false
    }
    return BootDecision(
        present = imagePresent,
        legacy = legacy,
        route = bootRoute(
            serverDeleteConfirmed = serverDeleteConfirmed,
            vaultImagePresent = imagePresent,
            durabilityHold = durabilityHold,
            vaultProvenAbsent = vaultProvenAbsent,
            legacyImage = legacy,
        ),
    )
}

/**
 * Does a completed account destroy SUPERSEDE an earlier residue-sweep hold?
 *
 * The hold exists because a cold-start orphan sweep unlinked residue without being able to prove the
 * unlink crash-durable. A destroy that completed proves image-bearing absence with its OWN required
 * `dirSync`, and retires both delete markers only after that proof — so once it has completed, the
 * earlier uncertainty is resolved by strictly stronger evidence. Leaving the hold raised would
 * withhold onboarding over a directory this delete has just proven durably clean, for the rest of the
 * process.
 *
 * Both conditions are required. [vaultProvenAbsent] alone is not enough — a fresh stat reports absence
 * the instant a file is unlinked — and `!`[serverDeleteConfirmed] is what says the destroy actually
 * reached its marker retire rather than throwing part-way.
 *
 * Extracted as a pure predicate so the decision is testable: it is the one behavioural change in an
 * otherwise-documentation delta, and it sits in the account-delete surface.
 */
internal fun destroySupersedesDurabilityHold(
    vaultProvenAbsent: Boolean,
    serverDeleteConfirmed: Boolean,
): Boolean = vaultProvenAbsent && !serverDeleteConfirmed

/** The outcome of a duress wipe, awaiting exactly one application to the UI. */
internal sealed interface BurnCompletion {
    /** The wipe proved itself durable. Present the fresh install (P2: visible reset). */
    data object Wiped : BurnCompletion

    /** The wipe failed. Present the UNIFORM failure — see invariant WB-1 before changing it. */
    data object Failed : BurnCompletion
}

/**
 * APPLY-ONCE for the burn's completion (0.9.2 Unit W-B, "snapshot → claim → apply/ack").
 *
 * The burn runs on the PROCESS scope under `NonCancellable` (WB-2), so it outlives the composition
 * that started it. An Activity recreation mid-wipe — a rotation, a configuration change, the system
 * rebuilding the window — must therefore not lose the outcome, and must not apply it twice.
 *
 * Extracted as a class so **apply-once is exercised against production code rather than a test
 * stand-in**: the same reason `runBootReconcile` owns its CAS claim instead of the composition owning
 * it. The shape is the one this codebase has converged on:
 *  - **snapshot** — read the pending completion without consuming it, so a composition that is about
 *    to be destroyed cannot swallow an outcome it will never render;
 *  - **claim** — CAS the exact snapshot away, so exactly one caller may apply it even if two
 *    compositions observe it concurrently;
 *  - **apply/ack** — the winner renders it; losers see `false` and do nothing.
 *
 * [pending] is observable so a freshly-created composition picks up an outcome signalled while it did
 * not exist.
 */
internal class BurnCompletionCoordinator {
    private val state = MutableStateFlow<BurnCompletion?>(null)

    /** Observable pending completion — collect this to learn an outcome landed. */
    val pending: StateFlow<BurnCompletion?> = state.asStateFlow()

    /** Publish an outcome. Overwrites any unclaimed one: the LATEST wipe outcome is the true one. */
    fun signal(outcome: BurnCompletion) {
        state.value = outcome
    }

    /** Read without consuming. */
    fun snapshot(): BurnCompletion? = state.value

    /**
     * Consume [snapshot] if it is still the pending one. Returns true to EXACTLY ONE caller per
     * signalled outcome; a caller that loses the race must not render.
     *
     * `compareAndSet` on the flow's value is the whole guarantee — a `value == snapshot` check
     * followed by a separate `value = null` would let two claimants both pass the check.
     */
    fun claim(snapshot: BurnCompletion): Boolean = state.compareAndSet(snapshot, null)
}

/**
 * THE DURESS WIPE ORCHESTRATION (0.9.2 Unit W-B) — extracted so the ORDER is testable against
 * production code rather than asserted in a comment.
 *
 * Three properties, and they are the whole contract:
 *  1. [raiseHold] runs STRICTLY BEFORE [obliterate] — never after, and never "on failure". A hold
 *     raised only in a catch block loses the crash window: a process death mid-obliterate would leave
 *     NO hold, and the next boot would present a fresh install over a wipe that was never proven
 *     durable. Raising first is what makes the failed-but-clean state safe.
 *  2. [lowerHold] runs ONLY after [obliterate] returns normally — i.e. only when the wipe proved
 *     every image-bearing path absent, fsynced the directory, and retired both markers. That is
 *     evidence strictly stronger than the doubt raised in (1), and it is the ONLY thing that may
 *     lower the hold.
 *  3. A throw from [obliterate] propagates with the hold STILL RAISED. The caller owns presentation;
 *     the hold is what makes a failed burn safe regardless of what the caller decides to show.
 *
 * This closes the round-6 HIGH structurally: the durability hold gains a third producer rather than a
 * second field. See [AppContainer.durabilityHold].
 */
internal fun runBurnWipe(
    raiseHold: () -> Unit,
    obliterate: () -> Unit,
    lowerHold: () -> Unit,
) {
    raiseHold()
    obliterate()
    lowerHold()
}

/**
 * The account-delete RETRY orchestration, extracted from the Compose lambda so the WIRING is
 * testable (follow-up review, Codex: the sole behavioural change of the W-A follow-up had no direct
 * test, and the truth-table tests over [bootRoute] cannot catch this CALL SITE reverting to the
 * weaker `!hasVault() && !serverDeleteConfirmed()` predicate it used to carry).
 *
 * Four properties, and they are the whole contract:
 *  1. [destroy] runs BEFORE [derive] — a decision taken over pre-destroy disk is meaningless.
 *  2. The verdict is the DERIVED one. This function does not re-decide, does not consult
 *     `hasVault()`, and does not assemble [bootRoute] inputs of its own. One authority.
 *  3. ONLY [BootRoute.ONBOARDING] is success. Every other route — including LOCKED over a residue
 *     sweep hold — leaves the caller on `Route.DeleteIncomplete` reporting failure.
 *  4. NO hold supersede. The completion callback owns that; doing it here too would be a second
 *     writer of the same state. See the call site for why the omission is accepted and tracked.
 *
 * [destroy] is expected to contain its own faults (the caller wraps it): a throw here propagates.
 */
internal suspend fun runDeleteRetry(
    destroy: suspend () -> Unit,
    derive: suspend () -> BootDecision,
): Boolean {
    destroy()
    return derive().route == BootRoute.ONBOARDING
}

/** Where a composition must route out of Splash on a cold start — see [bootRoute]. */
internal enum class BootRoute { DELETE_INCOMPLETE, LOCKED, ONBOARDING }

/**
 * One boot decision plus the disk facts it was taken from, so a caller applies a SINGLE consistent
 * snapshot instead of re-reading disk after the decision.
 */
internal data class BootDecision(
    val present: Boolean,
    val legacy: Boolean,
    val route: BootRoute,
)

/**
 * The cold-start route decision, extracted as a PURE function so the fail-closed precedence is
 * unit-testable without Compose.
 *
 * PRECEDENCE:
 *  1. **A CONFIRMED server delete outbids everything** — `Route.DeleteIncomplete` owns that state.
 *  2. **A LEGACY (v2) image goes to onboarding** — present but unusable, and its create() retires it.
 *     Ordered AFTER the confirmed marker (a legacy image must never preempt finishing a confirmed
 *     delete, whose create() would clear the marker authorising it) and BEFORE "a present image is a
 *     lock screen" (a legacy image IS present, so it would otherwise fall through to a lock screen the
 *     user can never pass).
 *  3. **A present image is a lock screen.**
 *  4. **A non-durable sweep withholds onboarding for the rest of this boot.** The residue is currently
 *     unlinked, so [AppContainer.vaultProvenAbsent] says "clean" and would authorise a fresh install —
 *     but a crash could replay the journal and bring it back. Absence that is not durable is not
 *     absence.
 *  5. **Onboarding requires PROVEN absence**, never merely "no `vault.bin`".
 *  6. Anything else is a lock screen.
 *
 * No parameter carries a default: omitting an input must be a COMPILE ERROR, not a silently weaker
 * call.
 */
internal fun bootRoute(
    serverDeleteConfirmed: Boolean,
    vaultImagePresent: Boolean,
    durabilityHold: Boolean,
    vaultProvenAbsent: Boolean,
    legacyImage: Boolean,
): BootRoute = when {
    serverDeleteConfirmed -> BootRoute.DELETE_INCOMPLETE
    // `&& vaultImagePresent` is DEFENCE, not behaviour: [deriveBootDecision] computes `legacy` only
    // when the image is present, so on every reachable input this conjunct is a no-op and every
    // existing row of the table is unchanged. Without it, the rule "only a PROVEN absence may present
    // a fresh install" lives in the derivation's probe guard and NOT in this router — a future caller
    // passing `legacyImage = true` over an image it could not stat would onboard over unprovable
    // material, because this arm outranks both the present arm and the proven-absence arm. The rule
    // belongs where it cannot be bypassed. (Found by a test written to pin the invariant, which
    // failed against this function: the router did not enforce what its caller was enforcing for it.)
    legacyImage && vaultImagePresent -> BootRoute.ONBOARDING
    vaultImagePresent -> BootRoute.LOCKED
    durabilityHold -> BootRoute.LOCKED
    vaultProvenAbsent -> BootRoute.ONBOARDING
    else -> BootRoute.LOCKED
}

/**
 * Clear a cache directory, FAIL-CLOSED. `!exists()` conflates "confirmed absent" with "stat failed",
 * and `listFiles()` returning null on an I/O or permission fault is exactly when plaintext is most
 * likely still present — a directory we cannot read is a directory we cannot claim to have emptied.
 */
internal fun clearCacheDir(cacheDir: java.io.File?): Boolean {
    if (cacheDir == null) return true
    if (java.nio.file.Files.notExists(cacheDir.toPath())) return true
    val entries = cacheDir.listFiles() ?: return false
    entries.forEach { runCatching { it.deleteRecursively() } }
    // Re-list to PROVE the clear rather than trusting deleteRecursively's bool (false on I/O error).
    val remaining = cacheDir.listFiles() ?: return false
    return remaining.isEmpty()
}
