/** Fournisseur de modèles de vérification, sans export de son implémentation. */
module exemple.verne.voisin {
    requires java.compiler;
    provides javax.annotation.processing.Processor with exemple.voisin.ProcesseurVoisin;
}
