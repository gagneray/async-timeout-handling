package fr.exemple.produits;
import lombok.Getter;
/** STUB : seule ASYNC_TIMEOUT fait partie du livrable. */
@Getter
public enum EnumCodeErreurTechnique {
    ERREUR_TECHNIQUE_INATTENDUE("TECH_000", "Erreur technique : %s"),
    ERREUR_APPEL_SOURCE("TECH_001", "Erreur lors de l'appel à la source : %s"),
    ASYNC_TIMEOUT("TECH_XXX", "La source n'a pas répondu dans le délai imparti : %s");
    private final String code; private final String template;
    EnumCodeErreurTechnique(String code, String template) { this.code = code; this.template = template; }
}
