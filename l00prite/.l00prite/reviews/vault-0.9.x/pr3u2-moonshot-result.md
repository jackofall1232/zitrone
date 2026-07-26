moon: asking kimi-k3...
**(A) Yes — accurate.** The doc states recovery is passphrase unlock + manual biometric disable/re-enable, and that retry does *not* self-heal because the present key (K2) lets the cipher initialise, then AEAD open fails → FAILED → wrap left in place, no re-offer. This matches facts 2–5 exactly, including the correct contrast that only the *absent/invalidated* key path (INVALIDATED/UNAVAILABLE) auto-clears and re-offers.

**(B) No new overclaim or inaccuracy.**
- "Shared Keystore alias raced, blob sealed under K1 while alias holds K2" — matches fact 1 (no single-flight, deleteKey+generateKey).
- "Cipher initialises but AEAD opening fails, plain FAILED, wrap left, no re-offer" — matches facts 2–4 (null cipher only on absent key; FAILED sets uniform message only).
- "Never repoints an established wrap (write-path guard), never destroys a pre-existing valid binding" — matches fact 6 (boundSlot==null || boundSlot==sessionSlot, fail-close; the race is strictly a first-bind).
- "No which-vault / second-vault exposure" — supported by fact 4's uniform failure message; the FAILED path discloses nothing slot-specific.
- "Self-inflicted availability glitch, not a deniability break" — consistent with the blast radius described in facts 1–6.
- The "atomicity fix is a scheduled follow-up" line is a roadmap statement, not a behavioral claim; it is not contradicted by facts 1–6 and introduces no technical inaccuracy.

**Verdict: CLEAN**
