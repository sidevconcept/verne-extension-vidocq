/** Compilation de gabarits Verne ; aucune bibliothèque tierce requise. */
module io.vidocq.verne.compilation {
    requires java.compiler;
    provides javax.annotation.processing.Processor
            with io.vidocq.verne.compilation.ProcesseurVerne;
}
