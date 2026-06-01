package com.nexgenai.service;

import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de PasswordGeneratorService.
 *
 * Vérifie :
 *   - La longueur du mot de passe généré
 *   - Le comportement par défaut si length < 8
 *   - La présence obligatoire des 4 catégories de caractères
 *   - L'entropie (résultats différents pour plusieurs appels)
 *
 * Pas de Spring context : instanciation directe.
 */
@DisplayName("PasswordGeneratorService — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PasswordGeneratorServiceTest {

    private final PasswordGeneratorService service = new PasswordGeneratorService();

    // ── Constantes utilitaires ────────────────────────────────────────────────
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS    = "0123456789";
    private static final String SPECIAL   = "!@#$%^&*()-_=+<>?";

    // ══════════════════════════════════════════════════════════════════════════
    // TC : Longueur du mot de passe
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-PWD-01 : generatePassword(12) → longueur exactement 12")
    void generatePassword_withLength12_returnsPasswordOf12Chars() {
        String password = service.generatePassword(12);
        assertEquals(12, password.length(),
            "Le mot de passe doit avoir exactement 12 caractères");
    }

    @Test
    @Order(2)
    @DisplayName("TC-PWD-02 : generatePassword(16) → longueur exactement 16")
    void generatePassword_withLength16_returnsPasswordOf16Chars() {
        String password = service.generatePassword(16);
        assertEquals(16, password.length());
    }

    @Test
    @Order(3)
    @DisplayName("TC-PWD-03 : generatePassword(20) → longueur exactement 20")
    void generatePassword_withLength20_returnsPasswordOf20Chars() {
        String password = service.generatePassword(20);
        assertEquals(20, password.length());
    }

    @Test
    @Order(4)
    @DisplayName("TC-PWD-04 : generatePassword(7) → length < 8, retourne 12 (valeur par défaut)")
    void generatePassword_withLength7_defaultsTo12() {
        String password = service.generatePassword(7);
        assertEquals(12, password.length(),
            "Pour length < 8, le service doit utiliser 12 par défaut");
    }

    @Test
    @Order(5)
    @DisplayName("TC-PWD-05 : generatePassword(1) → length < 8, retourne 12")
    void generatePassword_withLength1_defaultsTo12() {
        String password = service.generatePassword(1);
        assertEquals(12, password.length());
    }

    @Test
    @Order(6)
    @DisplayName("TC-PWD-06 : generatePassword(0) → length < 8, retourne 12")
    void generatePassword_withLength0_defaultsTo12() {
        String password = service.generatePassword(0);
        assertEquals(12, password.length());
    }

    @Test
    @Order(7)
    @DisplayName("TC-PWD-07 : generatePassword(8) → length == 8, traité comme < 8, retourne 12")
    void generatePassword_withLength8_treatedAsLessThan8_defaultsTo12() {
        String password = service.generatePassword(8);
        assertEquals(8, password.length(),
            "generatePassword(8) ne doit pas defaulter car 8 >= 8");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC : Présence des 4 catégories de caractères
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-PWD-08 : mot de passe généré contient au moins une minuscule")
    void generatePassword_alwaysContainsAtLeastOneLowercase() {
        for (int i = 0; i < 20; i++) {
            String pwd = service.generatePassword(12);
            assertTrue(containsAny(pwd, LOWERCASE),
                "Le mot de passe doit contenir au moins une minuscule : " + pwd);
        }
    }

    @Test
    @Order(9)
    @DisplayName("TC-PWD-09 : mot de passe généré contient au moins une majuscule")
    void generatePassword_alwaysContainsAtLeastOneUppercase() {
        for (int i = 0; i < 20; i++) {
            String pwd = service.generatePassword(12);
            assertTrue(containsAny(pwd, UPPERCASE),
                "Le mot de passe doit contenir au moins une majuscule : " + pwd);
        }
    }

    @Test
    @Order(10)
    @DisplayName("TC-PWD-10 : mot de passe généré contient au moins un chiffre")
    void generatePassword_alwaysContainsAtLeastOneDigit() {
        for (int i = 0; i < 20; i++) {
            String pwd = service.generatePassword(12);
            assertTrue(containsAny(pwd, DIGITS),
                "Le mot de passe doit contenir au moins un chiffre : " + pwd);
        }
    }

    @Test
    @Order(11)
    @DisplayName("TC-PWD-11 : mot de passe généré contient au moins un caractère spécial")
    void generatePassword_alwaysContainsAtLeastOneSpecialChar() {
        for (int i = 0; i < 20; i++) {
            String pwd = service.generatePassword(12);
            assertTrue(containsAny(pwd, SPECIAL),
                "Le mot de passe doit contenir au moins un caractère spécial : " + pwd);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC : Entropie / unicité
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(12)
    @DisplayName("TC-PWD-12 : 50 appels produisent des mots de passe distincts (entropie)")
    void generatePassword_50calls_produceDistinctPasswords() {
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            passwords.add(service.generatePassword(12));
        }
        // Sur 50 appels, on attend au moins 40 valeurs distinctes (probabilité extrêmement élevée)
        assertTrue(passwords.size() >= 40,
            "Les mots de passe générés doivent avoir de l'entropie : " + passwords.size() + " distincts");
    }

    @Test
    @Order(13)
    @DisplayName("TC-PWD-13 : mot de passe généré ne contient que des caractères attendus")
    void generatePassword_onlyContainsAllowedCharacters() {
        String allowed = LOWERCASE + UPPERCASE + DIGITS + SPECIAL;
        for (int i = 0; i < 10; i++) {
            String pwd = service.generatePassword(12);
            for (char c : pwd.toCharArray()) {
                assertTrue(allowed.indexOf(c) >= 0,
                    "Caractère inattendu dans le mot de passe : '" + c + "' dans " + pwd);
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private boolean containsAny(String s, String charSet) {
        for (char c : s.toCharArray()) {
            if (charSet.indexOf(c) >= 0) return true;
        }
        return false;
    }
}
