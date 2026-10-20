package fr.exemple.produits;
import lombok.Getter;
/** STUB */
@Getter
public enum EnumCodeErreurFonctionnel {
    AVERTISSEMENT_FONCTIONNEL("FONC_001", "Avertissement : %s");
    private final String code; private final String template;
    EnumCodeErreurFonctionnel(String code, String template) { this.code = code; this.template = template; }
}
