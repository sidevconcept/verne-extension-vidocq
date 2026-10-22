/** Banc de vérification ; ses tests compilent et exécutent de vraies applications avec le compilateur Verne. */
module io.vidocq.verne.verification {
    requires java.compiler;
    /** Lecteur XML indépendant, utilisé seulement comme oracle des tests de profil XML. */
    requires java.xml;
}
