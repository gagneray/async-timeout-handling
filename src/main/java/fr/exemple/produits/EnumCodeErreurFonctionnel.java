package fr.exemple.produits;
import lombok.Getter;
/** STUB */
@Getter
public enum EnumCodeErreurFonctionnel {
    AUCUN_RESULTAT("FONC_001", "Aucun résultat : %s");
    private final String code; private final String template;
    EnumCodeErreurFonctionnel(String code, String template) { this.code = code; this.template = template; }
}
