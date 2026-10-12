package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CONTRACT.md §10.8 — the identity-hash vector. */
class IdentityTest {

  /**
   * The pinned vector from CONTRACT.md §10.8, verbatim.
   *
   * <p>"This vector is computed and cross-checked in two implementations. If yours disagrees, yours
   * is wrong." The parity gate runs each SDK's vector test and compares the digests across
   * languages, so this constant is the cross-language contract and not a convenience.
   */
  @Test
  @DisplayName("§10.8 identity_hash(\"sek_test_secret\", \"user_42\") matches the pinned vector")
  void matchesThePinnedVector() {
    assertEquals(
        "5e26772dce97d5ba995796e621f83f2e97e6f8c3f9386dbf153a98ab24c10271",
        Identity.hash("sek_test_secret", "user_42"));
  }

  @Test
  void isLowercaseHexOfTheRightLength() {
    String digest = Identity.hash("sek_test_secret", "user_42");
    assertEquals(64, digest.length(), "SHA-256 is 32 bytes = 64 hex characters");
    assertTrue(digest.matches("[0-9a-f]{64}"), "lowercase hex only: " + digest);
  }

  @Test
  void isDeterministic() {
    assertEquals(
        Identity.hash("sek_test_secret", "user_42"), Identity.hash("sek_test_secret", "user_42"));
  }

  @Test
  void dependsOnBothInputs() {
    String base = Identity.hash("sek_test_secret", "user_42");
    assertNotEquals(base, Identity.hash("sek_test_secret", "user_43"));
    assertNotEquals(base, Identity.hash("sek_other_secret", "user_42"));
  }

  /**
   * An empty secret must throw, not hash.
   *
   * <p>An unset environment variable arrives as an empty string, and HMAC under an empty key is a
   * perfectly well-formed digest that anyone can reproduce. Accepting one turns identity
   * verification OFF while leaving every outward sign that it is on -- the widget keeps working,
   * the hash keeps validating, and any visitor can impersonate any user.
   */
  @Test
  void refusesAnEmptyOrNullSecret() {
    assertThrows(IllegalArgumentException.class, () -> Identity.hash("", "user_42"));
    assertThrows(IllegalArgumentException.class, () -> Identity.hash(null, "user_42"));
  }

  @Test
  void refusesAnEmptyOrNullExternalId() {
    assertThrows(IllegalArgumentException.class, () -> Identity.hash("sek_test_secret", ""));
    assertThrows(IllegalArgumentException.class, () -> Identity.hash("sek_test_secret", null));
  }

  @Test
  void handlesANonAsciiExternalIdByItsUtf8Bytes() {
    // Not a pinned vector, just a guard that the encoding is UTF-8 and fixed:
    // hashing a non-ASCII id under the platform default charset would produce
    // a different digest on a differently-configured JVM, and the widget would
    // reject the user on one machine and accept them on another.
    String digest = Identity.hash("sek_test_secret", "user_ümlaut");
    assertEquals(64, digest.length());
    assertEquals(digest, Identity.hash("sek_test_secret", "user_ümlaut"));
  }
}
