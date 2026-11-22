package io.vidocq.verne.verification;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Qualification du budget de génération face aux limites de javac et de la JVM (VERNE-011) : pire cas observé
 * avec sauts longs, pool de constantes et variables locales, chacun au plus près de 60 000 unités.
 */
@Timeout(120)
class LimitesJavacTest {
    @TempDir Path repertoire;

    private static final String MODELE = "package exemple; public record M(Boolean b, java.util.List<String> liste) {}";
    private static final String VALEUR = "new exemple.M(true, java.util.List.of(\"a\"))";

    /** 128 + 12 (paramètre) + 72 (boucle englobante) + 830 × 72 + 12 (fragment) = 59 984 unités. */
    @Test void elargitLesSautsDeBouclesAuBudgetMaximalSousLaLimiteDeMethode() throws Exception {
        String gabarit = "{@exemple.M m}{#for z in m.liste}" + "{#for x in m.liste}{/for}".repeat(830) + "{/for}fin";
        var code = rendre(gabarit, "exemple.gabarits.Bonjour.rendre(" + VALEUR + ")", "fin");
        verifierSautsLongs(code);
    }

    /** 128 + 12 + 72 (condition englobante) + 26 × 31 × 72 + 12 = 58 268 unités. */
    @Test void elargitLesSautsDeBlocsImbriquesRepetesSousLaLimiteDeMethode() throws Exception {
        var imbrique = new StringBuilder();
        for (int profondeur = 0; profondeur < 31; profondeur++) {
            imbrique.append("{#for x").append(profondeur).append(" in m.liste}");
        }
        String bloc = imbrique + "{/for}".repeat(31);
        String gabarit = "{@exemple.M m}{#if m.b}" + bloc.repeat(26) + "{/if}fin";
        var code = rendre(gabarit, "exemple.gabarits.Bonjour.rendre(" + VALEUR + ")", "fin");
        verifierSautsLongs(code);
    }

    /** 128 + 4 989 × 12 = 59 996 unités : chaque fragment distinct, séparé par un commentaire, est une constante. */
    @Test void conserveLePoolDeConstantesSousSaLimiteAvecLeMaximumDeFragments() throws Exception {
        var gabarit = new StringBuilder();
        var attendu = new StringBuilder();
        for (int index = 0; index < 4_989; index++) {
            String fragment = String.format(Locale.ROOT, "f%04d", index);
            gabarit.append(fragment).append("{! !}");
            attendu.append(fragment);
        }
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit.toString()),
                BancCompilation.application("exemple.gabarits.Bonjour.rendre()"), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu.toString(), execution.sortie()));
        int pool = classe(compilation).constantPool().size();
        assertTrue(pool > 4_989 && pool < 65_535, "Entrées du pool de constantes : " + pool);
        System.out.println("Pool de constantes avec 4 989 fragments distincts : " + pool + " entrées.");
    }

    /** 255 paramètres occupent 255 emplacements ; 31 boucles imbriquées ajoutent les leurs. */
    @Test void cumuleLesParametresMaximauxEtLesBouclesImbriqueesDansLesVariablesLocales() throws Exception {
        var gabarit = new StringBuilder();
        var arguments = new StringBuilder();
        for (int index = 0; index < 255; index++) {
            gabarit.append("{@exemple.M m").append(index).append('}');
            if (index > 0) arguments.append(", ");
            arguments.append("m");
        }
        for (int profondeur = 0; profondeur < 31; profondeur++) {
            gabarit.append("{#for x").append(profondeur).append(" in m").append(profondeur * 8).append(".liste}");
        }
        gabarit.append("{x30}").append("{/for}".repeat(31));
        var sources = Map.of("exemple/M.java", MODELE, "exemple/Application.java", """
                package exemple;
                public final class Application {
                    public static void main(String[] arguments) {
                        var m = %s;
                        System.out.print(exemple.gabarits.Bonjour.rendre(%s));
                    }
                }
                """.formatted(VALEUR, arguments));
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit.toString()), sources, true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals("a", execution.sortie()));
        var code = rendre(classe(compilation));
        assertTrue(code.maxLocals() > 255 && code.maxLocals() < 65_535, "Variables locales : " + code.maxLocals());
        System.out.println("Variables locales avec 255 paramètres et 31 boucles : " + code.maxLocals() + ".");
    }

    private CodeAttribute rendre(String gabarit, String expression, String attendu) throws Exception {
        var compilation = BancCompilation.compiler(repertoire, Map.of("bonjour.verne", gabarit),
                Map.of("exemple/M.java", MODELE, "exemple/Application.java", """
                        package exemple;
                        public final class Application {
                            public static void main(String[] arguments) { System.out.print(%s); }
                        }
                        """.formatted(expression)), true);
        assertTrue(compilation.reussie(), compilation.diagnostics());
        var execution = BancCompilation.executer(compilation);
        assertAll(() -> assertEquals(0, execution.codeSortie(), execution.erreurs()),
                () -> assertEquals(attendu, execution.sortie()));
        return rendre(classe(compilation));
    }

    /** Le mode des sauts longs est exercé : goto_w présent ; la méthode reste dans la borne historique de 60 000 octets. */
    private static void verifierSautsLongs(CodeAttribute code) {
        boolean sautsLongs = code.elementStream()
                .anyMatch(element -> element instanceof Instruction instruction && instruction.opcode() == Opcode.GOTO_W);
        assertTrue(sautsLongs, "La méthode devrait exiger des sauts longs ; taille " + code.codeLength());
        assertTrue(code.codeLength() <= 60_000, "Taille observée de la méthode : " + code.codeLength());
        System.out.println("Taille observée avec sauts longs : " + code.codeLength() + " octets.");
    }

    private static ClassModel classe(BancCompilation.Compilation compilation) throws Exception {
        return ClassFile.of().parse(compilation.classes().resolve("exemple/gabarits/Bonjour.class"));
    }

    private static CodeAttribute rendre(ClassModel modele) {
        var methode = modele.methods().stream().filter(candidat -> candidat.methodName().equalsString("rendre"))
                .findFirst().orElseThrow();
        return (CodeAttribute) methode.code().orElseThrow();
    }
}
